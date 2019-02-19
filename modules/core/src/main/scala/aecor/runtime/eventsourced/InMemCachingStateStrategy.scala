package aecor.runtime.eventsourced
import aecor.runtime.Eventsourced.Versioned
import cats.Monad
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

final class InMemCachingStateStrategy[F[_]: Monad, S, E] private (
  cache: Ref[F, Option[Versioned[S]]],
  inner: StateStrategy[F, S, E]
) extends StateStrategy[F, S, E] {

  override def recoverState(initial: Versioned[S]): F[Versioned[S]] =
    cache.get.flatMap {
      case Some(s) => s.pure[F]
      case None    => inner.recoverState(initial)
    }

  override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
    for {
      next <- inner.updateState(current, es)
      _ <- cache.set(next.some)
    } yield next
}

object InMemCachingStateStrategy {
  def create[F[_]: Sync, K, S, E](
    inner: StateStrategy[F, S, E]
  ): F[InMemCachingStateStrategy[F, S, E]] =
    Ref[F].of(none[Versioned[S]]).map(new InMemCachingStateStrategy(_, inner))
}
