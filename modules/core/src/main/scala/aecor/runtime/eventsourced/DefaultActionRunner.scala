package aecor.runtime.eventsourced
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ ActionT, EventsourcedBehavior, Folded }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ BehaviorFailure, Versioned }
import cats.MonadError
import cats.data.NonEmptyChain
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.implicits._

trait StateStrategy[F[_], K, S, E] {
  def key: K
  def update: (S, E) => Folded[S]
  def getState(initial: Versioned[S],
               fold: (Versioned[S], E) => Folded[Versioned[S]]): F[Versioned[S]]

  def updateState(currentVersion: Long, nextState: Versioned[S], es: NonEmptyChain[E]): F[Unit]

  final def useBehavior[M[_[_]]: FunctorK](
    entityBehavior: EventsourcedBehavior[M, F, S, E]
  )(implicit F: MonadError[F, Throwable]): M[F] =
    entityBehavior.actions.mapK(
      DefaultActionRunner(key, entityBehavior.create, entityBehavior.update, this)
    )
}

object StateStrategy {
  def basic[F[_], K, S, E](key: K, journal: EventJournal[F, K, E])(
    implicit F: MonadError[F, Throwable]
  ): BasicStateStrategy[F, K, S, E] = BasicStateStrategy(key, journal)
}

final class DefaultActionRunner[F[_], K, S, E] private (
  key: K,
  initial: S,
  strategy: StateStrategy[F, K, S, E]
)(implicit F: MonadError[F, Throwable])
    extends ActionRunner[F, S, E] {

  private val effectiveUpdate = (s: Versioned[S], e: E) =>
    strategy
      .update(s.value, e)
      .map(Versioned(_, s.version + 1))

  override def apply[A](action: ActionT[F, S, E, A]): F[A] =
    for {
      current <- strategy.getState(Versioned(initial, 0), effectiveUpdate)
      result <- action
                 .xmapState[Versioned[S]](_.withValue(_))(_.value)
                 .product(ActionT.read)
                 .run(current, effectiveUpdate)
      out <- result match {
              case Next((events, (a, next))) =>
                NonEmptyChain.fromChain(events) match {
                  case Some(es) =>
                    strategy.updateState(current.version, next, es).as(a)
                  case None =>
                    a.pure[F]
                }
              case Impossible =>
                F.raiseError[A](BehaviorFailure.illegalFold(key.toString))
            }
    } yield out
}

object DefaultActionRunner {
  def apply[F[_]: MonadError[?[_], Throwable], K, S, E](
    key: K,
    initial: S,
    update: (S, E) => Folded[S],
    stateStrategy: StateStrategy[F, K, S, E]
  ): ActionRunner[F, S, E] =
    new DefaultActionRunner(key, initial, update, stateStrategy)
}
