package aecor

import cats.Monad
import cats.mtl.MonadState
import monocle.Lens

package object testkit {
  implicit final class AecorMonocleLensOps[S, A](lens: Lens[S, A]) {
    def transformMonadState[F[_]](f: MonadState[F, S]): MonadState[F, A] = new MonadState[F, A] {
      override val monad: Monad[F] = f.monad
      override def get: F[A] = f.inspect(lens.get)
      override def set(s: A): F[Unit] = f.modify(lens.set(s))
      override def inspect[C](bc: A => C): F[C] = f.inspect(s => bc(lens.get(s)))
      override def modify(bb: A => A): F[Unit] = f.modify(lens.modify(bb))
    }
  }
}
