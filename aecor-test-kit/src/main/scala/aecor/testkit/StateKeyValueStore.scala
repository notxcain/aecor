package aecor.testkit

import aecor.util.KeyValueStore
import cats.Applicative
import cats.data.StateT

object StateKeyValueStore {
  def apply[F[_]: Applicative, S, K, A](
    extract: S => Map[K, A],
    update: (S, Map[K, A]) => S
  ): KeyValueStore[StateT[F, S, ?], K, A] =
    new StateKeyValueStore(extract, update)
}

class StateKeyValueStore[F[_]: Applicative, S, K, A](extract: S => Map[K, A],
                                                     update: (S, Map[K, A]) => S)
    extends KeyValueStore[StateT[F, S, ?], K, A] {

  override def setValue(key: K, value: A): StateT[F, S, Unit] =
    StateT
      .modify[F, Map[K, A]](_.updated(key, value))
      .transformS(extract, update)

  override def getValue(key: K): StateT[F, S, Option[A]] =
    StateT
      .inspect[F, Map[K, A], Option[A]](_.get(key))
      .transformS(extract, update)

}
