package aecor.tests.e2e

import java.util.UUID

import aecor.aggregate.runtime.EventsourcedBehavior.BehaviorFailure
import aecor.aggregate.runtime.{ EventsourcedBehavior, NoopSnapshotStore }
import aecor.aggregate.{ Correlation, Folder, Tagging }
import aecor.data.{ Folded, Handler }
import aecor.streaming.Committable
import aecor.tests.e2e.TestEventJournal.TestEventJournalState
import cats.data.StateT
import cats.implicits._
import cats.{ Monad, ~> }

import scala.collection.immutable._

trait E2eSupport {
  final type SpecF[A] = Either[BehaviorFailure, A]
  type SpecState
  final def mkJournal[E](
    extract: SpecState => TestEventJournalState[E],
    update: (SpecState, TestEventJournalState[E]) => SpecState
  ): TestEventJournal[SpecF, SpecState, E] =
    TestEventJournal[SpecF, SpecState, E](extract, update)

  final def mkBehavior[Op[_], S, E](
    name: String,
    correlation: Correlation[Op],
    opHandler: Op ~> Handler[S, Seq[E], ?],
    tagging: Tagging[E],
    journal: TestEventJournal[SpecF, SpecState, E]
  )(implicit folder: Folder[Folded, E, S]): Op ~> StateT[SpecF, SpecState, ?] =
    new (Op ~> StateT[SpecF, SpecState, ?]) {
      override def apply[A](fa: Op[A]): StateT[SpecF, SpecState, A] =
        EventsourcedBehavior[Op, S, E, StateT[SpecF, SpecState, ?]](
          name,
          correlation,
          opHandler,
          tagging,
          journal,
          Option.empty,
          NoopSnapshotStore.apply,
          StateT.pure(UUID.randomUUID())
        ).run(fa)
          .map(_._2)
    }

  final def wireProcess[F[_], S, In0](
    process: In0 => F[Unit],
    sources: FoldableSource[F, F, Committable[F, In0]]*
  ): WiredProcess[F] = {
    val process0 = process
    val sources0 = sources
    new WiredProcess[F] {
      type In = In0
      override val process: (In0) => F[Unit] = process0
      override val sources: Vector[FoldableSource[F, F, Committable[F, In0]]] = sources0.toVector
    }
  }

  sealed trait WiredProcess[F[_]] {
    type In
    val process: In => F[Unit]
    val sources: Vector[FoldableSource[F, F, Committable[F, In]]]
    final def run(implicit F: Monad[F]): F[Unit] =
      for {
        processed <- sources
                      .fold(FoldableSource.empty[F, F, Committable[F, In]])(_ merge _)
                      .foldM(0) {
                        case (cnt, committable) =>
                          committable
                            .traverse(process)
                            .flatMap(_ => committable.commit())
                            .map(_ => cnt + 1)
                      }
                      .flatten
        _ <- if (processed == 0)
              ().pure[F]
            else
              run
      } yield ()
  }

  def processes: Vector[WiredProcess[StateT[SpecF, SpecState, ?]]]

  final def wired[Op[_]](
    behavior: Op ~> StateT[SpecF, SpecState, ?]
  ): Op ~> StateT[SpecF, SpecState, ?] =
    new (Op ~> StateT[SpecF, SpecState, ?]) {
      override def apply[A](fa: Op[A]): StateT[SpecF, SpecState, A] =
        for {
          x <- behavior(fa)
          _ <- processes
                .map(_.run)
                .foldLeft(StateT.pure[SpecF, SpecState, Unit](())) { (l, r) =>
                  l.map2(r)((_, _) => ())
                }
        } yield x
    }

}
