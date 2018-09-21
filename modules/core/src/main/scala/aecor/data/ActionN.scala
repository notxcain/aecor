package aecor.data
import aecor.data.Folded.{Impossible, Next}
import aecor.data.Folded.syntax.impossible
import cats.{Applicative, Functor, Monad, MonadError, ~>}
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

  def sample[Env, E2](
                       getEnv: F[Env]
                     )(update: (Env, E) => E2)(extract: E2 => E)(implicit F: Monad[F]): ActionN[F, S, E2, A] =
    ActionN.liftF[F, S, E2, Env](getEnv).flatMap { env =>
      xmapEvents(update(env, _), extract)
    }

  def xmapEvents[E2](to: E => E2, from: E2 => E)(implicit F: Functor[F]): ActionN[F, S, E2, A] =
    ActionN { (s, u2, e2s) =>
      val u1 = (sx: S, e1: E) => u2(sx, to(e1))
      unsafeRun(s, u1, e2s.map(from)).map(_.map { x =>
        (x._1.map(to), x._2)
      })
    }

  def xmapState[S2](update: (S2, S) => S2)(extract: S2 => S): ActionN[F, S2, E, A] =
    ActionN {
      (s2, u2, es) =>
        unsafeRun(extract(s2), (s, e) => u2(update(s2, s), e).map(extract), es)
    }
}

object ActionN  extends ActionNFunctions with ActionNInstances {
  private[data] def apply[F[_], S, E, A](
                                    unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[Folded[(Chain[E], A)]]
                                  ): ActionN[F, S, E, A] = new ActionN(unsafeRun)

}

trait ActionNFunctions {
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

  def sample[F[_]: Monad, S, E1, Env, E2](getEnv: F[Env])(
    update: (Env, E1) => E2
  )(extract: E2 => E1): ActionN[F, S, E1, ?] ~> ActionN[F, S, E2, ?] =
    new (ActionN[F, S, E1, ?] ~> ActionN[F, S, E2, ?]) {
      override def apply[A](fa: ActionN[F, S, E1, A]): ActionN[F, S, E2, A] =
        fa.sample(getEnv)(update)(extract)
    }

  def xmapEvents[F[_]: Functor, S, E1, E2, R](
                                               to: E1 => E2,
                                               from: E2 => E1
                                             ): ActionN[F, S, E1, ?] ~> ActionN[F, S, E2, ?] =
    new (ActionN[F, S, E1, ?] ~> ActionN[F, S, E2, ?]) {
      override def apply[A](fa: ActionN[F, S, E1, A]): ActionN[F, S, E2, A] =
        fa.xmapEvents(to, from)
    }

  def xmapState[F[_]: Functor, S1, S2, E](update: (S2, S1) => S2)(extract: S2 => S1): ActionN[F, S1, E, ?] ~> ActionN[F, S2, E, ?] =
    new (ActionN[F, S1, E, ?] ~> ActionN[F, S2, E, ?]) {
      override def apply[A](fa: ActionN[F, S1, E, A]): ActionN[F, S2, E, A] = fa.xmapState(update)(extract)
    }
}

trait ActionNInstances extends ActionNLowerPriorityInstances {
  implicit def monadActionInstance[F[_], S, E, R](implicit F: MonadError[F, R]): MonadAction[ActionN[F, S, E, ?], F, S, E, R] =
    new ActionNMonadActionLiftInstance[F, S, E]() with MonadAction[ActionN[F, S, E, ?], F, S, E, R]  {
      override def reject[A](r: R): ActionN[F, S, E, A] = liftF(F.raiseError(r))
      override def handleErrorWith[A](fa: ActionN[F, S, E, A])(
        f: R => ActionN[F, S, E, A]
      ): ActionN[F, S, E, A] = ActionN {
        (s, u, es) =>
          F.handleErrorWith(fa.unsafeRun(s, u, es)) { r =>
            f(r).unsafeRun(s, u, es)
          }
      }
    }
}


trait ActionNLowerPriorityInstances {
  abstract class ActionNMonadActionLiftInstance[F[_], S, E](
                                                             implicit F: Monad[F]
                                                           ) extends MonadActionLift[ActionN[F, S, E, ?], F, S, E] {
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
  }
  implicit def monadActionLiftInstance[F[_], S, E](
                                                implicit F: Monad[F]
                                              ): MonadActionLift[ActionN[F, S, E, ?], F, S, E] =
    new ActionNMonadActionLiftInstance[F, S, E] {}
}
