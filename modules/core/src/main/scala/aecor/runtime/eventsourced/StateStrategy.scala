package aecor.runtime.eventsourced
import aecor.runtime.Eventsourced.Versioned
import cats.data.NonEmptyChain

trait StateStrategy[F[_], S, E] {
  def recoverState(snapshot: Versioned[S]): F[Versioned[S]]
  def updateState(currentState: Versioned[S], events: NonEmptyChain[E]): F[Versioned[S]]
}
