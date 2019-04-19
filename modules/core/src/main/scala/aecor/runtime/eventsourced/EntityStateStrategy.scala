package aecor.runtime.eventsourced
import aecor.data.ActionT
import aecor.runtime.Eventsourced.Versioned
import aecor.runtime.eventsourced.Stage.X
import cats.{ Monad, ~> }
import cats.implicits._

trait EntityStateStrategy[F[_], S, E] {
  def recoverState(from: Versioned[S]): F[Versioned[S]]
  def run[A](current: Versioned[S], action: ActionT[F, S, E, A]): F[(Versioned[S], A)]

  def recoverAndRun[A](from: Versioned[S],
                       action: ActionT[F, S, E, A])(implicit F: Monad[F]): F[A] =
    recoverState(from).flatMap { recovered =>
      run(recovered, action).map(_._2)
    }

  def init[A](initial: S)(implicit F: Monad[F]): ActionT[F, S, E, ?] ~> F =
    λ[ActionT[F, S, E, ?] ~> F] { action =>
      for {
        current <- recoverState(Versioned.zero(initial))
        (_, a) <- run(current, action)
      } yield a
    }
}

trait StateStrategyBuilder[K, F[_], S, E] {
  def x(key: K): F[X[F, S, E]]
  def create(key: K): F[EntityStateStrategy[F, S, E]]

}
