package aecor.runtime

import cats.{ Contravariant, Functor, Invariant, ~> }
import io.aecor.liberator.FunctorK

trait KeyValueStore[F[_], K, A] { self =>
  def setValue(key: K, value: A): F[Unit]
  def getValue(key: K): F[Option[A]]

  final def contramap[K2](f: K2 => K): KeyValueStore[F, K2, A] = new KeyValueStore[F, K2, A] {
    override def setValue(key: K2, value: A): F[Unit] = self.setValue(f(key), value)
    override def getValue(key: K2): F[Option[A]] = self.getValue(f(key))
  }
  final def mapK[G[_]](f: F ~> G): KeyValueStore[G, K, A] = new KeyValueStore[G, K, A] {
    override def setValue(key: K, value: A): G[Unit] = f(self.setValue(key, value))
    override def getValue(key: K): G[Option[A]] = f(self.getValue(key))
  }
  final def imap[B](f: A => B)(g: B => A)(implicit F: Functor[F]): KeyValueStore[F, K, B] =
    new KeyValueStore[F, K, B] {
      override def setValue(key: K, value: B): F[Unit] = self.setValue(key, g(value))

      override def getValue(key: K): F[Option[B]] = F.map(self.getValue(key))(_.map(f))
    }
}

object KeyValueStore {
  implicit def liberatorFunctorKInstance[K, A]: FunctorK[KeyValueStore[?[_], K, A]] =
    new FunctorK[KeyValueStore[?[_], K, A]] {
      override def mapK[F[_], G[_]](mf: KeyValueStore[F, K, A],
                                    fg: F ~> G): KeyValueStore[G, K, A] =
        mf.mapK(fg)
    }

  implicit def catsInvariantInstance[F[_]: Functor, K]: Invariant[KeyValueStore[F, K, ?]] =
    new Invariant[KeyValueStore[F, K, ?]] {
      override def imap[A, B](
        fa: KeyValueStore[F, K, A]
      )(f: A => B)(g: B => A): KeyValueStore[F, K, B] =
        fa.imap(f)(g)
    }

  implicit def catsContravarianFunctor[F[_], A]: Contravariant[KeyValueStore[F, ?, A]] =
    new Contravariant[KeyValueStore[F, ?, A]] {
      override def contramap[K1, K2](
        fa: KeyValueStore[F, K1, A]
      )(f: K2 => K1): KeyValueStore[F, K2, A] =
        fa.contramap(f)
    }
}
