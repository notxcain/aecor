package aecor.data.next

import aecor.data.Folded
import aecor.data.next.ActionT.{ ActionFailure, ActionResult }
import cats.data._
import cats.implicits._
import cats.{ Applicative, Functor, Monad, MonadError, ~> }

final class ActionT[F[_], S, E, R, A] private (
  val unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[ActionResult[R, E, A]]
) extends AnyVal {

  def run(current: S, update: (S, E) => Folded[S]): F[ActionResult[R, E, A]] =
    unsafeRun(current, update, Chain.empty)

  def map[B](f: A => B)(implicit F: Functor[F]): ActionT[F, S, E, R, B] = ActionT { (s, u, ue) =>
    unsafeRun(s, u, ue).map(_.map(x => (x._1, f(x._2))))
  }

  def flatMap[B](f: A => ActionT[F, S, E, R, B])(implicit F: Monad[F]): ActionT[F, S, E, R, B] =
    ActionT { (s0, u, es0) =>
      unsafeRun(s0, u, es0).flatMap {
        case Right((es1, a)) =>
          es1
            .foldM(s0)(u)
            .traverse(f(a).unsafeRun(_, u, es1))
            .map {
              case Folded.Next(s) =>
                s
              case Folded.Impossible =>
                Left(ActionFailure.ImpossibleFold)
            }
        case Left(af) =>
          F.pure(Left(af))
      }
    }

  def sample[Env, E2](
    getEnv: F[Env]
  )(update: (Env, E) => E2)(extract: E2 => E)(implicit F: Monad[F]): ActionT[F, S, E2, R, A] =
    ActionT.liftF[F, S, E2, R, Env](getEnv).flatMap { env =>
      xmapEvents(update(env, _), extract)
    }

  def xmapEvents[E2](to: E => E2, from: E2 => E)(implicit F: Functor[F]): ActionT[F, S, E2, R, A] =
    ActionT { (s, u2, e2s) =>
      val u1 = (sx: S, e1: E) => u2(sx, to(e1))
      unsafeRun(s, u1, e2s.map(from)).map(_.map { x =>
        (x._1.map(to), x._2)
      })
    }

  def xmapState[S2](update: (S2, S) => S2)(extract: S2 => S): ActionT[F, S2, E, R, A] =
    ActionT {
      (s2, u2, es) =>
        unsafeRun(extract(s2), (s, e) => u2(update(s2, s), e).map(extract), es)
    }
}

object ActionT {

  private def apply[F[_], S, E, R, A](
    unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[ActionResult[R, E, A]]
  ): ActionT[F, S, E, R, A] = new ActionT(unsafeRun)

  def sample[F[_]: Monad, S, E1, R, Env, E2](getEnv: F[Env])(
    update: (Env, E1) => E2
  )(extract: E2 => E1): ActionT[F, S, E1, R, ?] ~> ActionT[F, S, E2, R, ?] =
    new (ActionT[F, S, E1, R, ?] ~> ActionT[F, S, E2, R, ?]) {
      override def apply[A](fa: ActionT[F, S, E1, R, A]): ActionT[F, S, E2, R, A] =
        fa.sample(getEnv)(update)(extract)
    }

  def xmapEvents[F[_]: Functor, S, E1, E2, R](
    to: E1 => E2,
    from: E2 => E1
  ): ActionT[F, S, E1, R, ?] ~> ActionT[F, S, E2, R, ?] =
    new (ActionT[F, S, E1, R, ?] ~> ActionT[F, S, E2, R, ?]) {
      override def apply[A](fa: ActionT[F, S, E1, R, A]): ActionT[F, S, E2, R, A] =
        fa.xmapEvents(to, from)
    }

  def xmapState[F[_]: Functor, S1, S2, E, R](update: (S2, S1) => S2)(extract: S2 => S1): ActionT[F, S1, E, R, ?] ~> ActionT[F, S2, E, R, ?] =
    new (ActionT[F, S1, E, R, ?] ~> ActionT[F, S2, E, R, ?]) {
      override def apply[A](fa: ActionT[F, S1, E, R, A]): ActionT[F, S2, E, R, A] = fa.xmapState(update)(extract)
    }

  def read[F[_]: Applicative, S, E, R]: ActionT[F, S, E, R, S] =
    ActionT((s, _, es) => (es, s).asRight[ActionFailure[R]].pure[F])

  def append[F[_]: Applicative, S, E, R](e: NonEmptyChain[E]): ActionT[F, S, E, R, Unit] =
    ActionT((_, _, es0) => (es0 ++ e.toChain, ()).asRight[ActionFailure[R]].pure[F])

  def reject[F[_]: Applicative, S, E, R, A](r: R): ActionT[F, S, E, R, A] =
    ActionT(
      (_, _, _) => (ActionFailure.Rejection(r): ActionFailure[R]).asLeft[(Chain[E], A)].pure[F]
    )

  def reset[F[_]: Applicative, S, E, R]: ActionT[F, S, E, R, Unit] =
    ActionT((_, _, _) => (Chain.empty[E], ()).asRight[ActionFailure[R]].pure[F])

  def liftF[F[_]: Functor, S, E, R, A](fa: F[A]): ActionT[F, S, E, R, A] =
    ActionT((_, _, es) => fa.map(a => (es, a).asRight[ActionFailure[R]]))

  def pure[F[_]: Applicative, S, E, R, A](a: A): ActionT[F, S, E, R, A] =
    ActionT((_, _, es) => (es, a).asRight[ActionFailure[R]].pure[F])

  implicit def monadActionInstance[F[_], S, E, R](
    implicit F: Monad[F]
  ): MonadAction[ActionT[F, S, E, R, ?], F, S, E, R] =
    new MonadAction[ActionT[F, S, E, R, ?], F, S, E, R] {
      override def read: ActionT[F, S, E, R, S] = ActionT.read
      override def append(e: E, es: E*): ActionT[F, S, E, R, Unit] =
        ActionT.append(NonEmptyChain(e, es: _*))
      override def reject[A](r: R): ActionT[F, S, E, R, A] = ActionT.reject(r)
      override def liftF[A](fa: F[A]): ActionT[F, S, E, R, A] = ActionT.liftF(fa)
      override def map[A, B](fa: ActionT[F, S, E, R, A])(f: A => B): ActionT[F, S, E, R, B] =
        fa.map(f)

      override def flatMap[A, B](fa: ActionT[F, S, E, R, A])(
        f: A => ActionT[F, S, E, R, B]
      ): ActionT[F, S, E, R, B] = fa.flatMap(f)
      override def tailRecM[A, B](a: A)(
        f: A => ActionT[F, S, E, R, Either[A, B]]
      ): ActionT[F, S, E, R, B] = ActionT { (s, ue, es) =>
        F.tailRecM(a) { a =>
          f(a).unsafeRun(s, ue, es).flatMap {
            case Left(failure)          => F.pure(Right(Left(failure)))
            case Right((es2, Right(b))) => F.pure(Right(Right((es2, b))))
            case Right((es2, Left(na))) =>
              es2 match {
                case c if c.nonEmpty =>
                  ActionT
                    .append[F, S, E, R](NonEmptyChain.fromChainUnsafe(c))
                    .unsafeRun(s, ue, es)
                    .as(na.asLeft[ActionResult[R, E, B]])
                case _ =>
                  na.asLeft[ActionResult[R, E, B]].pure[F]
              }
          }
        }
      }
      override def pure[A](x: A): ActionT[F, S, E, R, A] = ActionT.pure(x)
      override def handleErrorWith[A](
        fa: ActionT[F, S, E, R, A]
      )(f: R => ActionT[F, S, E, R, A]): ActionT[F, S, E, R, A] = ActionT { (s, u, es) =>
        fa.unsafeRun(s, u, es).flatMap {
          case Left(ActionFailure.Rejection(r)) =>
            f(r).unsafeRun(s, u, es)
          case other =>
            other.pure[F]
        }
      }
    }

  type ActionResult[+R, +E, +A] = Either[ActionFailure[R], (Chain[E], A)]
  sealed abstract class ActionFailure[+R]
  object ActionFailure {
    final case class Rejection[+R](a: R) extends ActionFailure[R]
    final case object ImpossibleFold extends ActionFailure[Nothing]
  }
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
