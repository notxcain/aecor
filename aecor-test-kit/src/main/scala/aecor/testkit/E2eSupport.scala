package aecor.testkit

import aecor.data._
import aecor.testkit.Eventsourced.{ BehaviorFailure, RunningState }
import aecor.util.NoopKeyValueStore
import cats.data.{ EitherT, StateT }
import cats.implicits._
import cats.{ Eval, Monad, ~> }

import scala.collection.immutable._

trait E2eSupport {
  final type SpecF[A] = EitherT[Eval, BehaviorFailure, A]
  type SpecState
  final def mkJournal[E](
    extract: SpecState => StateEventJournal.State[E],
    update: (SpecState, StateEventJournal.State[E]) => SpecState
  ): StateEventJournal[SpecF, SpecState, E] =
    StateEventJournal[SpecF, SpecState, E](extract, update)

  final def mkBehavior[Op[_], S, E](
    behavior: EventsourcedBehavior[StateT[SpecF, SpecState, ?], Op, S, E],
    correlation: Correlation[Op],
    tagging: Tagging[E],
    journal: StateEventJournal[SpecF, SpecState, E]
  ): Op ~> StateT[SpecF, SpecState, ?] =
    new (Op ~> StateT[SpecF, SpecState, ?]) {
      override def apply[A](fa: Op[A]): StateT[SpecF, SpecState, A] =
        Eventsourced[StateT[SpecF, SpecState, ?], Op, S, E](
          correlation,
          behavior,
          tagging,
          journal,
          Option.empty,
          NoopKeyValueStore[StateT[SpecF, SpecState, ?], String, RunningState[S]]
        ).run(fa)
          .map(_._2)
    }

  final def wireProcess[F[_], S, In](process: In => F[Unit],
                                     sources: Processable[F, In]*): WiredProcess[F] = {
    val process0 = process
    val sources0 = sources
    type In0 = In
    new WiredProcess[F] {
      type In = In0
      override val process: (In) => F[Unit] = process0
      override val sources: Vector[Processable[F, In]] =
        sources0.toVector
    }
  }

  sealed abstract class WiredProcess[F[_]] {
    protected type In
    protected val process: In => F[Unit]
    protected val sources: Vector[Processable[F, In]]
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
