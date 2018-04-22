package aecor.testkit

import io.aecor.liberator.Invocation
import aecor.data._
import aecor.runtime.{ EventJournal, Eventsourced }
import aecor.runtime.Eventsourced._
import cats.data.StateT
import cats.implicits._
import cats.mtl.MonadState
import cats.{ FlatMap, Monad, MonadError, ~> }
import io.aecor.liberator.{ FunctorK, ReifiedInvocations }
import monocle.Lens

import scala.collection.immutable._

object E2eSupport {

  final def behavior[M[_[_]]: FunctorK, F[_], S, E, I](
    behavior: EventsourcedBehaviorT[M, F, S, E],
    journal: EventJournal[F, I, E]
  )(implicit M: ReifiedInvocations[M], F: MonadError[F, BehaviorFailure]): I => Behavior[M, F] =
    Eventsourced[M, F, S, E, I](behavior, journal)

  class Runtime[F[_]] {
    final def deploy[K, M[_[_]]: FunctorK: ReifiedInvocations](
      f: K => Behavior[M, F]
    )(implicit F: FlatMap[F]): K => M[F] = { key =>
      val actions = f(key).actions
      ReifiedInvocations[M].mapInvocations {
        new (Invocation[M, ?] ~> F) {
          final override def apply[A](invocation: Invocation[M, A]): F[A] =
            invocation.invoke[PairT[F, Behavior[M, F], ?]](actions).map(_._2)
        }
      }
    }
  }

  abstract class Processes[F[_]](items: Vector[F[Unit]]) {
    protected type S
    protected implicit def F: MonadState[F, S]

    final private implicit def monad = F.monad

    final def runProcesses: F[Unit] =
      for {
        stateBefore <- F.get
        _ <- items.sequence
        stateAfter <- F.get
        _ <- if (stateAfter == stateBefore) {
              ().pure[F]
            } else {
              runProcesses
            }
      } yield ()

    final def wiredK[I, M[_[_]]](behavior: I => M[F])(implicit M: FunctorK[M]): I => M[F] =
      i =>
        M.mapK(behavior(i), new (F ~> F) {
          override def apply[A](fa: F[A]): F[A] =
            fa <* runProcesses
        })

    final def wired[A, B](f: A => F[B]): A => F[B] =
      f.andThen(_ <* runProcesses)
  }

  object Processes {
    def apply[F[_], S0](items: F[Unit]*)(implicit F0: MonadState[F, S0]): Processes[F] =
      new Processes[F](items.toVector) {
        final override type S = S0
        override implicit def F: MonadState[F, S] = F0
      }
  }
}

trait E2eSupport {
  import cats.mtl.instances.state._

  type SpecState

  type F[A] = StateT[Either[BehaviorFailure, ?], SpecState, A]

  final def mkJournal[I, E](lens: Lens[SpecState, StateEventJournal.State[I, E]],
                            tagging: Tagging[I]): StateEventJournal[F, I, SpecState, E] =
    StateEventJournal[F, I, SpecState, E](lens, tagging)

  final def wireProcess[In](process: In => F[Unit],
                            source: Processable[F, In],
                            sources: Processable[F, In]*)(implicit F: Monad[F]): F[Unit] =
    sources
      .fold(source)(_ merge _)
      .process(process)
      .void

  val runtime = new E2eSupport.Runtime[F]
}
