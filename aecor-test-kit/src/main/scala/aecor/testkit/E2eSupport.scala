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
  final def mkJournal[I, E](
    extract: SpecState => StateEventJournal.State[I, E],
    update: (SpecState, StateEventJournal.State[I, E]) => SpecState
  ): StateEventJournal[SpecF, I, SpecState, E] =
    StateEventJournal[SpecF, I, SpecState, E](extract, update)

  final def mkBehavior[I, Op[_], S, E](
    behavior: EventsourcedBehavior[StateT[SpecF, SpecState, ?], Op, S, E],
    tagging: Tagging[I],
    journal: StateEventJournal[SpecF, I, SpecState, E]
  ): I => Op ~> StateT[SpecF, SpecState, ?] =
    i =>
      new (Op ~> StateT[SpecF, SpecState, ?]) {
        override def apply[A](fa: Op[A]): StateT[SpecF, SpecState, A] =
          Eventsourced[StateT[SpecF, SpecState, ?], I, Op, S, E](
            behavior,
            tagging,
            journal,
            Option.empty,
            NoopKeyValueStore[StateT[SpecF, SpecState, ?], I, RunningState[S]]
          ).apply(i)
            .run(fa)
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

  final def wiredK[I, Op[_]](
    behavior: I => Op ~> StateT[SpecF, SpecState, ?]
  ): I => Op ~> StateT[SpecF, SpecState, ?] =
    i =>
      new (Op ~> StateT[SpecF, SpecState, ?]) {
        override def apply[A](fa: Op[A]): StateT[SpecF, SpecState, A] =
          for {
            x <- behavior(i)(fa)
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
