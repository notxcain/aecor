package aecor

import cats.data.EitherT
import cats.{ Applicative, Monad }

trait MonadAction[F[_], S, E] extends Monad[F] {
  def read: F[S]
  def append(es: E, other: E*): F[Unit]
  def reset: F[Unit]
}

trait MonadActionReject[F[_], S, E, R] extends MonadAction[F, S, E] {
  def reject[A](r: R): F[A]
}

object MonadActionReject {
  implicit def eitherTMonadActionRejectInstance[I[_]: Applicative, S, E, R](
    implicit F: MonadAction[I, S, E],
    eitherTMonad: Monad[EitherT[I, R, ?]]
  ): MonadActionReject[EitherT[I, R, ?], S, E, R] =
    new MonadActionReject[EitherT[I, R, ?], S, E, R] {
      override def reject[A](r: R): EitherT[I, R, A] = EitherT.leftT(r)
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

trait MonadActionLift[I[_], F[_], S, E] extends MonadAction[I, S, E] {
  def liftF[A](fa: F[A]): I[A]
}

trait MonadActionLiftReject[I[_], F[_], S, E, R]
    extends MonadActionLift[I, F, S, E]
    with MonadActionReject[I, S, E, R]

object MonadActionLiftReject {
  implicit def eitherTMonadActionLiftRejectInstance[I[_], F[_], S, E, R](
    implicit I: MonadActionLift[I, F, S, E],
    eitherTMonad: Monad[EitherT[I, R, ?]]
  ): MonadActionLiftReject[EitherT[I, R, ?], F, S, E, R] =
    new MonadActionLiftReject[EitherT[I, R, ?], F, S, E, R] {
      override def reject[A](r: R): EitherT[I, R, A] = EitherT.leftT(r)
      override def liftF[A](fa: F[A]): EitherT[I, R, A] = EitherT.right(I.liftF(fa))
      override def read: EitherT[I, R, S] = EitherT.right(I.read)
      override def append(es: E, other: E*): EitherT[I, R, Unit] =
        EitherT.right(I.append(es, other: _*))
      override def reset: EitherT[I, R, Unit] = EitherT.right(I.reset)
      override def pure[A](x: A): EitherT[I, R, A] = EitherT.pure[I, R](x)
      override def map[A, B](fa: EitherT[I, R, A])(f: A => B): EitherT[I, R, B] = fa.map(f)
      override def flatMap[A, B](fa: EitherT[I, R, A])(f: A => EitherT[I, R, B]): EitherT[I, R, B] =
        fa.flatMap(f)
      override def tailRecM[A, B](a: A)(f: A => EitherT[I, R, Either[A, B]]): EitherT[I, R, B] =
        eitherTMonad.tailRecM(a)(f)
    }
}
