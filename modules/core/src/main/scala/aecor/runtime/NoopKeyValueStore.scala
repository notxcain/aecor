package aecor.runtime

import cats.Applicative
import cats.implicits._

sealed class NoopKeyValueStore[F[_]: Applicative, K, V] extends KeyValueStore[F, K, V] {
  override def setValue(key: K, value: V): F[Unit] =
    ().pure[F]

  override def getValue(key: K): F[Option[V]] =
    none[V].pure[F]
  override def deleteValue(key: K): F[Unit] = ().pure[F]
}

object NoopKeyValueStore {
  def apply[F[_]: Applicative, K, V]: NoopKeyValueStore[F, K, V] = new NoopKeyValueStore[F, K, V]
}
