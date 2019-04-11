package aecor.runtime.eventsourced
import aecor.data.ActionT
import aecor.runtime.Eventsourced.Versioned
import cats.data.NonEmptyChain

trait EntityStateStrategy[F[_], S, E] {
  def recoverState(from: Versioned[S]): F[Versioned[S]]
  def runAction[A](current: Versioned[S], action: ActionT[F, S, E, A]): F[(Versioned[S], A)]
  def updateState(currentState: Versioned[S], events: NonEmptyChain[E]): F[Versioned[S]]
}

trait StateStrategy[K, F[_], S, E] {
  def focus(key: K): F[EntityStateStrategy[F, S, E]]
}
