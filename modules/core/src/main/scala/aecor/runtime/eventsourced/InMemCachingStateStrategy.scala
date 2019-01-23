package aecor.runtime.eventsourced
import aecor.data.Folded
import aecor.runtime.Eventsourced.Versioned
import cats.Monad
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

final class InMemCachingStateStrategy[F[_]: Monad, K, S, E] private (
  cache: Ref[F, Option[Versioned[S]]],
  inner: StateStrategy[F, K, S, E]
) extends StateStrategy[F, K, S, E] {

  override val key: K = inner.key
  override def getState(initial: Versioned[S],
                        fold: (Versioned[S], E) => Folded[Versioned[S]]): F[Versioned[S]] =
    cache.get.flatMap {
      case Some(s) => s.pure[F]
      case None    => inner.getState(initial, fold).flatTap(s => cache.set(s.some))
    }

  override def updateState(currentVersion: Long,
                           nextState: Versioned[S],
                           es: NonEmptyChain[E]): F[Unit] =
    for {
      _ <- inner.updateState(currentVersion, nextState, es)
      _ <- cache.set(nextState.some)
    } yield ()
}

object InMemCachingStateStrategy {
  def create[F[_]: Sync, K, S, E](
    inner: StateStrategy[F, K, S, E]
  ): F[InMemCachingStateStrategy[F, K, S, E]] =
    Ref[F].of(none[Versioned[S]]).map(new InMemCachingStateStrategy(_, inner))
}
