package aecor.data

import cats.data.Chain
import cats.{Monad, MonadError}

trait ActionRun[M[_], F[_], S, E] {
  def run[A](ma: M[A])(current: S, update: (S, E) => Folded[S]): F[Folded[(Chain[E], A)]]
}

trait MonadActionBase[F[_], S, E] extends Monad[F] {
  def read: F[S]
  def append(es: E, other: E*): F[Unit]
}

trait MonadActionReject[F[_], S, E, R] extends MonadActionBase[F, S, E] with MonadError[F, R] {
  def reject[A](r: R): F[A]
  override def raiseError[A](e: R): F[A] = reject(e)
}

trait MonadActionLift[M[_], F[_], S, E] extends MonadActionBase[M, S, E] {
  def liftF[A](fa: F[A]): M[A]
}

trait MonadAction[M[_], F[_], S, E, R]
  extends MonadActionReject[M, S, E, R]
    with MonadActionLift[M, F, S, E]