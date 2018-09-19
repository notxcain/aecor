package aecor.data
import aecor.data.Folded.{Impossible, Next}
import aecor.data.Folded.syntax.impossible
import cats.{Applicative, Functor, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.implicits._
import Folded.syntax._

final class ActionN[F[_], S, E, A] private (
                                             val unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[Folded[(Chain[E], A)]]
                                           ) extends AnyVal {

  def run(current: S, update: (S, E) => Folded[S]): F[Folded[(Chain[E], A)]] =
    unsafeRun(current, update, Chain.empty)

  def map[B](f: A => B)(implicit F: Functor[F]): ActionN[F, S, E, B] = ActionN {
    (s, u, es) =>
      unsafeRun(s, u, es).map(_.map(x => (x._1, f(x._2))))
  }

  def flatMap[B](f: A => ActionN[F, S, E, B])(implicit F: Monad[F]): ActionN[F, S, E, B] =
    ActionN { (s0, u, es0) =>
      unsafeRun(s0, u, es0).flatMap {
        case Folded.Next((es1, a)) =>
          es1
            .foldM(s0)(u)
            .traverse(f(a).unsafeRun(_, u, es1)).map(_.flatten)
        case Impossible =>
          impossible[(Chain[E], B)].pure[F]
      }
    }
}

object ActionN {
  private def apply[F[_], S, E, A](
                                    unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[Folded[(Chain[E], A)]]
                                  ): ActionN[F, S, E, A] = new ActionN(unsafeRun)

  def read[F[_]: Applicative, S, E]: ActionN[F, S, E, S] =
    ActionN((s, _, es) => (es, s).next.pure[F])

  def append[F[_]: Applicative, S, E](e: NonEmptyChain[E]): ActionN[F, S, E, Unit] =
    ActionN((_, _, es0) => (es0 ++ e.toChain, ()).next.pure[F])

  def reset[F[_]: Applicative, S, E]: ActionN[F, S, E, Unit] =
    ActionN((_, _, _) => (Chain.empty[E], ()).next.pure[F])

  def liftF[F[_]: Functor, S, E, A](fa: F[A]): ActionN[F, S, E,  A] =
    ActionN((_, _, es) => fa.map(a => (es, a).next))

  def pure[F[_]: Applicative, S, E, A](a: A): ActionN[F, S, E, A] =
    ActionN((_, _, es) => (es, a).next.pure[F])

  implicit def monadActionInstance[F[_], S, E](
                                                   implicit F: Monad[F]
                                                 ): MonadActionLift[ActionN[F, S, E, ?], F, S, E] =
    new MonadActionLift[ActionN[F, S, E, ?], F, S, E] with ActionRun[ActionN[F, S, E, ?], F, S, E] {
      override def read: ActionN[F, S, E, S] = ActionN.read
      override def append(e: E, es: E*): ActionN[F, S, E, Unit] =
        ActionN.append(NonEmptyChain(e, es: _*))
      override def liftF[A](fa: F[A]): ActionN[F, S, E, A] = ActionN.liftF(fa)
      override def map[A, B](fa: ActionN[F, S, E, A])(f: A => B): ActionN[F, S, E, B] =
        fa.map(f)

      override def flatMap[A, B](fa: ActionN[F, S, E, A])(
        f: A => ActionN[F, S, E, B]
      ): ActionN[F, S, E, B] = fa.flatMap(f)
      override def tailRecM[A, B](a: A)(
        f: A => ActionN[F, S, E, Either[A, B]]
      ): ActionN[F, S, E, B] = ActionN { (s, ue, es) =>
        F.tailRecM(a) { a =>
          f(a).unsafeRun(s, ue, es).flatMap[Either[A, Folded[(Chain[E], B)]]] {
            case Impossible          =>  impossible[(Chain[E], B)].asRight[A].pure[F]
            case Next((es2, Right(b))) => (es2, b).next.asRight[A].pure[F]
            case Next((es2, Left(na))) =>
              es2 match {
                case c if c.nonEmpty =>
                  ActionN
                    .append[F, S, E](NonEmptyChain.fromChainUnsafe(c))
                    .unsafeRun(s, ue, es)
                    .as(na.asLeft[Folded[(Chain[E], B)]])
                case _ =>
                  na.asLeft[Folded[(Chain[E], B)]].pure[F]
              }
          }
        }
      }
      override def pure[A](x: A): ActionN[F, S, E, A] = ActionN.pure(x)
      override def run[A](ma: ActionN[F, S, E, A])(
        current: S,
        update: (S, E) => Folded[S]
      ): F[Folded[(Chain[E], A)]] = ma.run(current, update)
    }
}
