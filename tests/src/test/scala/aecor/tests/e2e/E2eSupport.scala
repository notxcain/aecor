package aecor.tests.e2e

import java.util.UUID

import aecor.aggregate.runtime.EventsourcedBehavior.BehaviorFailure
import aecor.aggregate.runtime.{ EventsourcedBehavior, NoopSnapshotStore }
import aecor.aggregate.{ Correlation, Folder, Tagging }
import aecor.data.{ Folded, Handler }
import aecor.tests.e2e.TestEventJournal.TestEventJournalState
import cats.data.{ EitherT, StateT }
import cats.implicits._
import cats.{ Eval, Monad, ~> }

import scala.collection.immutable._

trait E2eSupport {
  final type SpecF[A] = EitherT[Eval, BehaviorFailure, A]
  type SpecState
  final def mkJournal[E](
    extract: SpecState => TestEventJournalState[E],
    update: (SpecState, TestEventJournalState[E]) => SpecState
  ): TestEventJournal[SpecF, SpecState, E] =
    TestEventJournal[SpecF, SpecState, E](extract, update)

  final def mkBehavior[Op[_], S, E](
    name: String,
    correlation: Correlation[Op],
    opHandler: Op ~> Handler[StateT[SpecF, SpecState, ?], S, Seq[E], ?],
    tagging: Tagging[E],
    journal: TestEventJournal[SpecF, SpecState, E]
  )(implicit folder: Folder[Folded, E, S]): Op ~> StateT[SpecF, SpecState, ?] =
    new (Op ~> StateT[SpecF, SpecState, ?]) {
      override def apply[A](fa: Op[A]): StateT[SpecF, SpecState, A] =
        EventsourcedBehavior(
          name,
          correlation,
          opHandler,
          tagging,
          journal,
          Option.empty,
          NoopSnapshotStore[StateT[SpecF, SpecState, ?], S],
          StateT.inspect[SpecF, SpecState, UUID](_ => UUID.randomUUID())
        ).run(fa)
          .map(_._2)
    }

  final def wireProcess[F[_], S, In0](process: In0 => F[Unit],
                                      sources: Processable[F, In0]*): WiredProcess[F] = {
    val process0 = process
    val sources0 = sources
    new WiredProcess[F] {
      type In = In0
      override val process: (In0) => F[Unit] = process0
      override val sources: Vector[Processable[F, In0]] =
        sources0.toVector
    }
  }

  sealed trait WiredProcess[F[_]] {
    type In
    val process: In => F[Unit]
    val sources: Vector[Processable[F, In]]
    final def run(implicit F: Monad[F]): F[Unit] =
      sources
        .fold(Processable.empty[F, In])(_ merge _)
        .process(process)
        .void

  }

  def processes: Vector[WiredProcess[StateT[SpecF, SpecState, ?]]]

  def otherStuff: Vector[StateT[SpecF, SpecState, Unit]] = Vector.empty

  private def runProcesses: StateT[SpecF, SpecState, Unit] =
    for {
      stateBefore <- StateT.get[SpecF, SpecState]
      _ <- (processes.map(_.run) ++ otherStuff).sequence
      stateAfter <- StateT.get[SpecF, SpecState]
      _ <- if (stateAfter == stateBefore) {
            ().pure[StateT[SpecF, SpecState, ?]]
          } else {
            runProcesses
          }
    } yield ()

  final def wiredK[Op[_]](
    behavior: Op ~> StateT[SpecF, SpecState, ?]
  ): Op ~> StateT[SpecF, SpecState, ?] =
    new (Op ~> StateT[SpecF, SpecState, ?]) {
      override def apply[A](fa: Op[A]): StateT[SpecF, SpecState, A] =
        for {
          x <- behavior(fa)
          _ <- runProcesses
        } yield x
    }

  final def wired[A, B](f: A => StateT[SpecF, SpecState, B]): A => StateT[SpecF, SpecState, B] =
    new (A => StateT[SpecF, SpecState, B]) {
      override def apply(a: A): StateT[SpecF, SpecState, B] =
        for {
          x <- f(a)
          _ <- runProcesses
        } yield x
    }

}
