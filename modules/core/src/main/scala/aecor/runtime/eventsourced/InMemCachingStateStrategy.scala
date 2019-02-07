package aecor.runtime.eventsourced
import aecor.data.ActionT
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

  override def getState(initial: Versioned[S]): F[Versioned[S]] =
    cache.get.flatMap {
      case Some(s) => s.pure[F]
      case None    => inner.getState(initial).flatTap(s => cache.set(s.some))
    }

  override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
    for {
      next <- inner.updateState(current, es)
      _ <- cache.set(next.some)
    } yield next
  override def run[A](action: ActionT[F, S, E, A]): F[A] = inner.run(action)
}

object InMemCachingStateStrategy {
  def create[F[_]: Sync, K, S, E](
    inner: StateStrategy[F, K, S, E]
  ): F[InMemCachingStateStrategy[F, K, S, E]] =
    Ref[F].of(none[Versioned[S]]).map(new InMemCachingStateStrategy(_, inner))
}
