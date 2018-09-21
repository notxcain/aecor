package aecor.testkit

import io.aecor.liberator.Invocation
import aecor.data.{EventsourcedBehavior, _}
import aecor.runtime.{EventJournal, Eventsourced}
import aecor.runtime.Eventsourced._
import cats.data.{EitherT, StateT}
import cats.effect.{IO, Sync}
import cats.implicits._
import cats.mtl.MonadState
import cats.{FlatMap, Monad, ~>}
import io.aecor.liberator.{FunctorK, ReifiedInvocations}
import monocle.Lens

import scala.collection.immutable._

object E2eSupport {

  final def behavior[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    behavior: EventsourcedBehavior[M, F, S, E],
    journal: EventJournal[F, K, E]
  )(implicit M: ReifiedInvocations[M]): K => F[M[F]] =
    Eventsourced[M, F, S, E, K](behavior, journal)

  final class Runtime[F[_]] {
    def deploy[K, M[_[_]]: FunctorK: ReifiedInvocations, R](
      f:   K => F[EitherK[M, F, R]]
    )(implicit F: FlatMap[F]): K => M[EitherT[F, R, ?]] = { key =>
      ReifiedInvocations[M].mapInvocations {
        new (Invocation[M, ?] ~> EitherT[F, R, ?]) {
          final override def apply[A](invocation: Invocation[M, A]): EitherT[F, R, A] =
            EitherT(f(key).map(_.value).flatMap(me => invocation.invoke(me).value))
        }
      }
    }
  }

  abstract class Processes[F[_]](items: Vector[F[Unit]]) {
    protected type S
    protected implicit def F: MonadState[F, S]

    final private implicit val monad = F.monad

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

  type F[A] = StateT[EitherT[IO, BehaviorFailure, ?], SpecState, A]

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
