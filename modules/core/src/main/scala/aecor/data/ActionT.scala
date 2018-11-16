package aecor.data
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.Folded.syntax.impossible
import cats.{ Applicative, Functor, Monad, MonadError, ~> }
import cats.data.{ Chain, NonEmptyChain }
import cats.implicits._
import Folded.syntax._
import aecor.{ MonadActionLift, MonadActionLiftReject }
import cats.tagless.FunctorK

final class ActionT[F[_], S, E, A] private (
  val unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[Folded[(Chain[E], A)]]
) extends AnyVal {

  def run(current: S, update: (S, E) => Folded[S]): F[Folded[(Chain[E], A)]] =
    unsafeRun(current, update, Chain.empty)

  def map[B](f: A => B)(implicit F: Functor[F]): ActionT[F, S, E, B] = ActionT { (s, u, es) =>
    unsafeRun(s, u, es).map(_.map(x => (x._1, f(x._2))))
  }

  def flatMap[B](f: A => ActionT[F, S, E, B])(implicit F: Monad[F]): ActionT[F, S, E, B] =
    ActionT { (s0, u, es0) =>
      unsafeRun(s0, u, es0).flatMap {
        case Folded.Next((es1, a)) =>
          es1
            .foldM(s0)(u)
            .traverse(f(a).unsafeRun(_, u, es1))
            .map(_.flatten)
        case Impossible =>
          impossible[(Chain[E], B)].pure[F]
      }
    }

  def sample[Env, E2](
    getEnv: F[Env]
  )(update: (Env, E) => E2)(extract: E2 => E)(implicit F: Monad[F]): ActionT[F, S, E2, A] =
    ActionT.liftF[F, S, E2, Env](getEnv).flatMap { env =>
      xmapEvents(update(env, _), extract)
    }

  def xmapEvents[E2](to: E => E2, from: E2 => E)(implicit F: Functor[F]): ActionT[F, S, E2, A] =
    ActionT { (s, u2, e2s) =>
      val u1 = (sx: S, e1: E) => u2(sx, to(e1))
      unsafeRun(s, u1, e2s.map(from)).map(_.map { x =>
        (x._1.map(to), x._2)
      })
    }

  def xmapState[S2](update: (S2, S) => S2)(extract: S2 => S): ActionT[F, S2, E, A] =
    ActionT { (s2, u2, es) =>
      unsafeRun(extract(s2), (s, e) => u2(update(s2, s), e).map(extract), es)
    }

  def product[B](that: ActionT[F, S, E, B])(implicit F: Monad[F]): ActionT[F, S, E, (A, B)] =
    flatMap(a => that.map(b => (a, b)))

  def mapK[G[_]](m: F ~> G): ActionT[G, S, E, A] =
    ActionT { (s, u, es) =>
      m(unsafeRun(s, u, es))
    }
}

object ActionT extends ActionTFunctions with ActionTInstances {
  private[data] def apply[F[_], S, E, A](
    unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[Folded[(Chain[E], A)]]
  ): ActionT[F, S, E, A] = new ActionT(unsafeRun)
}

trait ActionTFunctions {
  def read[F[_]: Applicative, S, E]: ActionT[F, S, E, S] =
    ActionT((s, _, es) => (es, s).next.pure[F])

  def append[F[_]: Applicative, S, E](e: NonEmptyChain[E]): ActionT[F, S, E, Unit] =
    ActionT((_, _, es0) => (es0 ++ e.toChain, ()).next.pure[F])

  def reset[F[_]: Applicative, S, E]: ActionT[F, S, E, Unit] =
    ActionT((_, _, _) => (Chain.empty[E], ()).next.pure[F])

  def liftF[F[_]: Functor, S, E, A](fa: F[A]): ActionT[F, S, E, A] =
    ActionT((_, _, es) => fa.map(a => (es, a).next))

  def pure[F[_]: Applicative, S, E, A](a: A): ActionT[F, S, E, A] =
    ActionT((_, _, es) => (es, a).next.pure[F])

  def sample[F[_]: Monad, S, E1, Env, E2](
    getEnv: F[Env]
  )(update: (Env, E1) => E2)(extract: E2 => E1): ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?] =
    new (ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?]) {
      override def apply[A](fa: ActionT[F, S, E1, A]): ActionT[F, S, E2, A] =
        fa.sample(getEnv)(update)(extract)
    }

  def xmapEvents[F[_]: Functor, S, E1, E2, R](
    to: E1 => E2,
    from: E2 => E1
  ): ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?] =
    new (ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?]) {
      override def apply[A](fa: ActionT[F, S, E1, A]): ActionT[F, S, E2, A] =
        fa.xmapEvents(to, from)
    }

  def xmapState[F[_]: Functor, S1, S2, E](
    update: (S2, S1) => S2
  )(extract: S2 => S1): ActionT[F, S1, E, ?] ~> ActionT[F, S2, E, ?] =
    new (ActionT[F, S1, E, ?] ~> ActionT[F, S2, E, ?]) {
      override def apply[A](fa: ActionT[F, S1, E, A]): ActionT[F, S2, E, A] =
        fa.xmapState(update)(extract)
    }
}

trait ActionTInstances extends ActionTLowerPriorityInstances1 {
  implicit def monadActionLiftRejectInstance[F[_], S, E, R](
    implicit F0: MonadError[F, R]
  ): MonadActionLiftReject[ActionT[F, S, E, ?], F, S, E, R] =
    new MonadActionLiftReject[ActionT[F, S, E, ?], F, S, E, R]
    with ActionTMonadActionLiftInstance[F, S, E] {
      override protected implicit def F: Monad[F] = F0
      override def reject[A](r: R): ActionT[F, S, E, A] = ActionT.liftF(F0.raiseError[A](r))
    }

  implicit def actionTFunctorKInstance[S, E, A]: FunctorK[ActionT[?[_], S, E, A]] =
    new FunctorK[ActionT[?[_], S, E, A]] {
      def mapK[F[_], G[_]](a: ActionT[F, S, E, A])(f: ~>[F, G]): ActionT[G, S, E, A] = a.mapK(f)
    }
}

trait ActionTLowerPriorityInstances1 {
  trait ActionTMonadActionLiftInstance[F[_], S, E]
      extends MonadActionLift[ActionT[F, S, E, ?], F, S, E] {
    protected implicit def F: Monad[F]
    override def read: ActionT[F, S, E, S] = ActionT.read
    override def append(e: E, es: E*): ActionT[F, S, E, Unit] =
      ActionT.append(NonEmptyChain(e, es: _*))
    override def reset: ActionT[F, S, E, Unit] = ActionT.reset
    override def map[A, B](fa: ActionT[F, S, E, A])(f: A => B): ActionT[F, S, E, B] =
      fa.map(f)

    override def flatMap[A, B](fa: ActionT[F, S, E, A])(
      f: A => ActionT[F, S, E, B]
    ): ActionT[F, S, E, B] = fa.flatMap(f)
    override def tailRecM[A, B](a: A)(f: A => ActionT[F, S, E, Either[A, B]]): ActionT[F, S, E, B] =
      ActionT { (s, ue, es) =>
        F.tailRecM(a) { a =>
          f(a).unsafeRun(s, ue, es).flatMap[Either[A, Folded[(Chain[E], B)]]] {
            case Impossible            => impossible[(Chain[E], B)].asRight[A].pure[F]
            case Next((es2, Right(b))) => (es2, b).next.asRight[A].pure[F]
            case Next((es2, Left(na))) =>
              es2 match {
                case c if c.nonEmpty =>
                  ActionT
                    .append[F, S, E](NonEmptyChain.fromChainUnsafe(c))
                    .unsafeRun(s, ue, es)
                    .as(na.asLeft[Folded[(Chain[E], B)]])
                case _ =>
                  na.asLeft[Folded[(Chain[E], B)]].pure[F]
              }
          }
        }
      }
    override def pure[A](x: A): ActionT[F, S, E, A] = ActionT.pure(x)
    override def liftF[A](fa: F[A]): ActionT[F, S, E, A] = ActionT.liftF(fa)
  }

  implicit def monadActionLiftInstance[F[_], S, E](
    implicit F0: Monad[F]
  ): MonadActionLift[ActionT[F, S, E, ?], F, S, E] =
    new ActionTMonadActionLiftInstance[F, S, E] {
      override protected implicit def F: Monad[F] = F0
    }

}
