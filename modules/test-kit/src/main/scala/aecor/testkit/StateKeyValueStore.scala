package aecor.testkit

import aecor.runtime.KeyValueStore
import cats.mtl.MonadState
import monocle.Lens

object StateKeyValueStore {
  final class Builder[F[_]] {
    def apply[S: MonadState[F, ?], K, A](lens: Lens[S, Map[K, A]]): KeyValueStore[F, K, A] =
      new StateKeyValueStore(lens)
  }
  def apply[F[_]]: Builder[F] = new Builder[F]
}

class StateKeyValueStore[F[_]: MonadState[?[_], S], S, K, A](lens: Lens[S, Map[K, A]])
    extends KeyValueStore[F, K, A] {
  private val F = lens.transformMonadState(MonadState[F, S])
  override def setValue(key: K, value: A): F[Unit] =
    F.modify(_.updated(key, value))

  override def getValue(key: K): F[Option[A]] =
    F.inspect(_.get(key))
  override def deleteValue(key: K): F[Unit] =
    F.modify(_ - key)
}
