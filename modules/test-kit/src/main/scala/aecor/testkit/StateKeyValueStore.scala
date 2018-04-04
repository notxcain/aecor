package aecor.testkit

import aecor.util.KeyValueStore
import cats.mtl.MonadState
import monocle.Lens

object StateKeyValueStore {
  def apply[F[_]: MonadState[?[_], S], S, K, A](lens: Lens[S, Map[K, A]]): KeyValueStore[F, K, A] =
    new StateKeyValueStore(lens)
}

class StateKeyValueStore[F[_]: MonadState[?[_], S], S, K, A](lens: Lens[S, Map[K, A]])
    extends KeyValueStore[F, K, A] {
  private val F = lens.transformMonadState(MonadState[F, S])
  override def setValue(key: K, value: A): F[Unit] =
    F.modify(_.updated(key, value))

  override def getValue(key: K): F[Option[A]] =
    F.inspect(_.get(key))

}
