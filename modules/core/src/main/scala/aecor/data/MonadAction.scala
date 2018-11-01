package aecor.data

import cats.data.EitherT
import cats.{ Monad, MonadError }

trait MonadActionBase[F[_], S, E] extends Monad[F] {
  def read: F[S]
  def append(es: E, other: E*): F[Unit]
  def reset: F[Unit]
}

trait MonadActionReject[F[_], S, E, R] extends MonadActionBase[F, S, E] with MonadError[F, R] {
  def reject[A](r: R): F[A]
  override def raiseError[A](e: R): F[A] = reject(e)
}

object MonadActionReject {
  implicit def eitherTMonadActionRejectInstance[M[_], F[_], S, E, R](
    implicit F: MonadActionLift[M, F, S, E]
  ): MonadActionReject[EitherT[M, R, ?], S, E, R] =
    MonadAction.eitherTMonadActionInstance[M, F, S, E, R]
}

trait MonadActionLift[I[_], F[_], S, E] extends MonadActionBase[I, S, E] {
  def liftF[A](fa: F[A]): I[A]
}

trait MonadAction[I[_], F[_], S, E, R]
    extends MonadActionReject[I, S, E, R]
    with MonadActionLift[I, F, S, E]

object MonadAction {
  implicit def eitherTMonadActionInstance[I[_], F[_], S, E, R](
    implicit F: MonadActionLift[I, F, S, E],
    eitherTMonad: Monad[EitherT[I, R, ?]]
  ): MonadAction[EitherT[I, R, ?], F, S, E, R] =
    new MonadAction[EitherT[I, R, ?], F, S, E, R] {
      override def reject[A](r: R): EitherT[I, R, A] = EitherT.leftT(r)

      override def handleErrorWith[A](fa: EitherT[I, R, A])(
        f: R => EitherT[I, R, A]
      ): EitherT[I, R, A] = fa.recoverWith { case r => f(r) }
      override def liftF[A](fa: F[A]): EitherT[I, R, A] = EitherT.right(F.liftF(fa))
      override def read: EitherT[I, R, S] = EitherT.right(F.read)
      override def append(es: E, other: E*): EitherT[I, R, Unit] =
        EitherT.right(F.append(es, other: _*))
      override def reset: EitherT[I, R, Unit] = EitherT.right(F.reset)
      override def pure[A](x: A): EitherT[I, R, A] = EitherT.pure[I, R](x)
      override def map[A, B](fa: EitherT[I, R, A])(f: A => B): EitherT[I, R, B] = fa.map(f)
      override def flatMap[A, B](fa: EitherT[I, R, A])(f: A => EitherT[I, R, B]): EitherT[I, R, B] =
        fa.flatMap(f)
      override def tailRecM[A, B](a: A)(f: A => EitherT[I, R, Either[A, B]]): EitherT[I, R, B] =
        eitherTMonad.tailRecM(a)(f)
    }
}
