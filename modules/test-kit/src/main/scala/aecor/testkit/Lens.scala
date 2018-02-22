package aecor.testkit

import cats.Monad
import cats.mtl.MonadState

final case class Lens[A, B](get: A => B, update: (A, B) => A) { outer =>
  def transformMonadState[F[_]](f: MonadState[F, A]): MonadState[F, B] = new MonadState[F, B] {
    override val monad: Monad[F] = f.monad
    override def get: F[B] = f.inspect(outer.get)
    override def set(s: B): F[Unit] = f.modify(a => update(a, s))
    override def inspect[C](bc: B => C): F[C] = f.inspect(a => bc(outer.get(a)))
    override def modify(bb: B => B): F[Unit] = f.modify(a => update(a, bb(outer.get(a))))
  }
}
