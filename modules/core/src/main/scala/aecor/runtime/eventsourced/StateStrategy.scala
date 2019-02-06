package aecor.runtime.eventsourced
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ ActionT, Folded }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ BehaviorFailure, Versioned }
import cats.data.NonEmptyChain
import cats.tagless.FunctorK
import cats.tagless.implicits._
import cats.{ MonadError, ~> }

trait StateStrategy[F[_], K, S, E] { outer =>
  val key: K
  def getState(initial: Versioned[S]): F[Versioned[S]]
  def updateState(currentState: Versioned[S], events: NonEmptyChain[E]): F[Versioned[S]]

  def run[A](action: ActionT[F, S, E, A]): F[A]

  final def asFunctionK: ActionT[F, S, E, ?] ~> F =
    Lambda[ActionT[F, S, E, ?] ~> F](outer.run(_))

  final def runActions[M[_[_]]: FunctorK](actions: M[ActionT[F, S, E, ?]]): M[F] =
    actions.mapK(this.asFunctionK)
}

object StateStrategy {
  def basic[F[_], K, S, E](
    key: K,
    initial: S,
    update: (S, E) => Folded[S],
    journal: EventJournal[F, K, E]
  )(implicit F: MonadError[F, Throwable]): BasicStateStrategy[F, K, S, E] =
    BasicStateStrategy(key, initial, update, journal)
}
