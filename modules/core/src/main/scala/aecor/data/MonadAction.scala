package aecor.data

import cats.data.{Chain, EitherT}
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

object MonadActionReject {
  implicit def eitherTMonadActionRejectInstance[M[_], F[_], S, E, R](implicit F: MonadActionLift[M, F, S, E]): MonadActionReject[EitherT[M, R, ?], S, E, R] =
    MonadAction.eitherTMonadActionInstance[M, F, S, E, R]
}

trait MonadActionLift[M[_], F[_], S, E] extends MonadActionBase[M, S, E] {
  def liftF[A](fa: F[A]): M[A]
}

trait MonadAction[M[_], F[_], S, E, R]
  extends MonadActionReject[M, S, E, R]
    with MonadActionLift[M, F, S, E]

object MonadAction {
  implicit def eitherTMonadActionInstance[M[_], F[_], S, E, R](implicit F: MonadActionLift[M, F, S, E]): MonadAction[EitherT[M, R, ?], F, S, E, R] =
    new MonadAction[EitherT[M, R, ?], F, S, E, R] {
      private val eitherTMonad = EitherT.catsDataMonadErrorForEitherT[M, R]
      override def reject[A](r: R): EitherT[M, R, A] = EitherT.leftT(r)

      override def handleErrorWith[A](fa: EitherT[M, R, A])(
        f: R => EitherT[M, R, A]
      ): EitherT[M, R, A] = fa.recoverWith { case r => f(r) }
      override def liftF[A](fa: F[A]): EitherT[M, R, A] = EitherT.right(F.liftF(fa))
      override def read: EitherT[M, R, S] = EitherT.right(F.read)
      override def append(es: E, other: E*): EitherT[M, R, Unit] = EitherT.right(F.append(es, other: _*))
      override def pure[A](x: A): EitherT[M, R, A] = EitherT.pure[M, R](x)
      override def map[A, B](fa: EitherT[M, R, A])(f: A => B): EitherT[M, R, B] = fa.map(f)
      override def flatMap[A, B](fa: EitherT[M, R, A])(
        f: A => EitherT[M, R, B]
      ): EitherT[M, R, B] = fa.flatMap(f)
      override def tailRecM[A, B](a: A)(
        f: A => EitherT[M, R, Either[A, B]]
      ): EitherT[M, R, B] = eitherTMonad.tailRecM(a)(f)
}
}