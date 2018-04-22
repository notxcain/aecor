package aecor.runtime

import cats.{ Functor, ~> }

trait KeyValueStore[F[_], K, A] { self =>
  def setValue(key: K, value: A): F[Unit]
  def getValue(key: K): F[Option[A]]

  def contramap[K2](f: K2 => K): KeyValueStore[F, K2, A] = new KeyValueStore[F, K2, A] {
    override def setValue(key: K2, value: A): F[Unit] = self.setValue(f(key), value)
    override def getValue(key: K2): F[Option[A]] = self.getValue(f(key))
  }
  def mapK[G[_]](f: F ~> G): KeyValueStore[G, K, A] = new KeyValueStore[G, K, A] {
    override def setValue(key: K, value: A): G[Unit] = f(self.setValue(key, value))
    override def getValue(key: K): G[Option[A]] = f(self.getValue(key))
  }
  def imap[B](ab: A => B, ba: B => A)(implicit F: Functor[F]): KeyValueStore[F, K, B] =
    new KeyValueStore[F, K, B] {
      override def setValue(key: K, value: B): F[Unit] = self.setValue(key, ba(value))

      override def getValue(key: K): F[Option[B]] = F.map(self.getValue(key))(_.map(ab))
    }
}
