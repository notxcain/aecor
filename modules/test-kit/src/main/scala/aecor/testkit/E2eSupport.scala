package aecor.testkit

import aecor.data._
import aecor.testkit.Eventsourced.{ BehaviorFailure, EventEnvelope, InternalState }
import aecor.util.NoopKeyValueStore
import cats.data.StateT
import cats.implicits._
import cats.{ Monad, MonadError, ~> }
import io.aecor.liberator.{ Algebra, FunctorK }

import scala.collection.immutable._

trait E2eSupport {
  final type SpecF[A] = Either[BehaviorFailure, A]
  type SpecState
  final def mkJournal[I, E](
    extract: SpecState => StateEventJournal.State[I, E],
    update: (SpecState, StateEventJournal.State[I, E]) => SpecState
  ): StateEventJournal[SpecF, I, SpecState, E] =
    StateEventJournal[SpecF, I, SpecState, E](extract, update)

  final def deploy[M[_[_]], F[_], S, E, I](
    behavior: EventsourcedBehaviorT[M, F, S, E],
    tagging: Tagging[I],
    journal: EventJournal[F, I, EventEnvelope[E]]
  )(implicit M: Algebra[M], MF: FunctorK[M], F: MonadError[F, BehaviorFailure]): I => M[F] = { id =>
    val routeTo: I => Behavior[M, F] =
      Eventsourced[M, F, S, E, I](
        behavior,
        tagging,
        journal,
        Option.empty,
        NoopKeyValueStore[F, I, InternalState[S]]
      )
    val actionK = M.toFunctionK[PairT[F, Behavior[M, F], ?]](routeTo(id).actions)
    M.fromFunctionK {
      new (M.Out ~> F) {
        override def apply[A](operation: M.Out[A]): F[A] =
          F.map(actionK(operation))(_._2)
      }
    }
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

  final def wiredK[I, M[_[_]]](
    behavior: I => M[StateT[SpecF, SpecState, ?]]
  )(implicit M: FunctorK[M]): I => M[StateT[SpecF, SpecState, ?]] =
    i =>
      M.mapK(behavior(i), new (StateT[SpecF, SpecState, ?] ~> StateT[SpecF, SpecState, ?]) {
        override def apply[A](fa: StateT[SpecF, SpecState, A]): StateT[SpecF, SpecState, A] =
          for {
            x <- fa
            _ <- runProcesses
          } yield x
      })

  final def wired[A, B](f: A => StateT[SpecF, SpecState, B]): A => StateT[SpecF, SpecState, B] =
    new (A => StateT[SpecF, SpecState, B]) {
      override def apply(a: A): StateT[SpecF, SpecState, B] =
        for {
          x <- f(a)
          _ <- runProcesses
        } yield x
    }

}
