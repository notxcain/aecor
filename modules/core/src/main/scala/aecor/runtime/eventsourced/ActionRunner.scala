package aecor.runtime.eventsourced

import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ ActionT, EventsourcedBehavior, Folded }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ BehaviorFailure, Versioned }
import cats.data.NonEmptyChain
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.implicits._
import cats.{ Functor, MonadError, ~> }

final case class ActionRunner[K, F[_], S, E, X <: EntityStateStrategy[F, S, E]](
  runner: ActionT[F, S, E, ?] ~> F,
  strategy: X
) {
  def modifyStrategy[Y <: EntityStateStrategy[F, S, E]](f: X => Y): ActionRunner[F, S, E, Y] =
    ActionRunner(runner, f(strategy))

  def evalModifyStrategy[Y <: EntityStateStrategy[F, S, E]](
    f: X => F[Y]
  )(implicit F: Functor[F]): F[ActionRunner[F, S, E, Y]] =
    f(strategy).map(ActionRunner(runner, _))

  def run[A](action: ActionT[F, S, E, A]): F[A] =
    runner(strategy)(action)

  def asFunctionK: ActionT[F, S, E, ?] ~> F =
    Lambda[ActionT[F, S, E, ?] ~> F](run(_))

  def runActions[K, M[_[_]]: FunctorK](key: K, actions: M[ActionT[F, S, E, ?]]): M[F] =
    actions.mapK(this.asFunctionK)
}

object ActionRunner {
  def apply[F[_], K, S, E](key: K, initial: S, stateStrategy: StateStrategy[K, F, S, E])(
    implicit F: MonadError[F, Throwable]
  ): F[ActionT[F, S, E, ?] ~> F] = stateStrategy.focus(key).map { ess =>
    val unfold = Lambda[Folded ~> F] {
      case Next(x) => x.pure[F]
      case Impossible =>
        F.raiseError(
          BehaviorFailure
            .illegalFold(key.toString)
        )
    }
    Lambda[ActionT[F, S, E, ?] ~> F] { action =>
      for {
        current <- ess.recoverState(Versioned.zero(initial))
        result <- action.run(current.value, update)
        (es, a) <- unfold(result)
        _ <- NonEmptyChain
              .fromChain(es)
              .traverse_(nes => ess.updateState(current, nes))
      } yield a
    }
  }

}
