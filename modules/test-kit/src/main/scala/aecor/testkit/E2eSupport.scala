package aecor.testkit

import aecor.data.{ EventsourcedBehavior, _ }
import aecor.runtime.{ EventJournal, Eventsourced }
import cats.data.StateT
import cats.effect.{ IO, Sync }
import cats.implicits._
import cats.mtl.MonadState
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import cats.~>
import fs2.Stream
import monocle.Lens

import scala.collection.immutable._

object E2eSupport {

  final class Runtime[F[_]] {
    def deploy[M[_[_]]: FunctorK, S, E, K](
      behavior: EventsourcedBehavior[M, F, S, E],
      journal: EventJournal[F, K, E]
    )(implicit F: Sync[F]): K => M[F] =
      Eventsourced[M, F, S, E, K](behavior, journal)
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

    final def wireK[I, M[_[_]]: FunctorK](behavior: I => M[F]): I => M[F] =
      i =>
        behavior(i).mapK(new (F ~> F) {
          override def apply[A](fa: F[A]): F[A] =
            fa <* runProcesses
        })

    final def wire[A, B](f: F[B]): F[B] =
      f <* runProcesses
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

  type F[A] = StateT[IO, SpecState, A]

  final def mkJournal[I, E](lens: Lens[SpecState, StateEventJournal.State[I, E]],
                            tagging: Tagging[I]): StateEventJournal[F, I, SpecState, E] =
    StateEventJournal[F, I, SpecState, E](lens, tagging)

  final def wireProcess[In](process: In => F[Unit],
                            source: Stream[F, Committable[F, In]],
                            sources: Stream[F, Committable[F, In]]*)(implicit F: Sync[F]): F[Unit] =
    sources
      .fold(source)(_ ++ _)
      .evalMap(_.process(process))
      .compile
      .drain

  val runtime = new E2eSupport.Runtime[F]
}
