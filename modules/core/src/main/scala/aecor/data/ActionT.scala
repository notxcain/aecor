package aecor.data
import aecor.data.Folded.syntax.{ impossible, _ }
import aecor.data.Folded.{ Impossible, Next }
import aecor.{ MonadActionLift, MonadActionLiftReject }
import cats.data.{ Chain, NonEmptyChain }
import cats.implicits._
import cats.tagless.FunctorK
import cats.{ Applicative, Functor, Monad, MonadError, ~> }

final class ActionT[F[_], S, E, A] private (
  val unsafeRun: (Fold[Folded, S, E], Chain[E]) => F[Folded[(Chain[E], A)]]
) extends AnyVal {

  def run(fold: Fold[Folded, S, E]): F[Folded[(Chain[E], A)]] =
    unsafeRun(fold, Chain.empty)

  def map[B](f: A => B)(implicit F: Functor[F]): ActionT[F, S, E, B] = ActionT { (fold, es) =>
    unsafeRun(fold, es).map(_.map(x => (x._1, f(x._2))))
  }

  def flatMap[B](f: A => ActionT[F, S, E, B])(implicit F: Monad[F]): ActionT[F, S, E, B] =
    ActionT { (fold, es0) =>
      unsafeRun(fold, es0).flatMap {
        case Folded.Next((es1, a)) =>
          f(a).unsafeRun(fold, es1)
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
    ActionT { (fold, events) =>
      unsafeRun(fold.contramap(to), events.map(from)).map(_.map { x =>
        (x._1.map(to), x._2)
      })
    }

  def expand[S2](set: (S2, S) => S2)(get: S2 => S): ActionT[F, S2, E, A] =
    ActionT { (f, es) =>
      unsafeRun(f.focus(get)(set), es)
    }

  def product[B](that: ActionT[F, S, E, B])(implicit F: Monad[F]): ActionT[F, S, E, (A, B)] =
    flatMap(a => that.map(b => (a, b)))

  def mapK[G[_]](fg: F ~> G): ActionT[G, S, E, A] =
    ActionT { (f, es) =>
      fg(unsafeRun(f, es))
    }

  def zipWithRead(implicit F: Monad[F]): ActionT[F, S, E, (A, S)] =
    this.product(ActionT.read[F, S, E])
}

object ActionT extends ActionTFunctions with ActionTInstances {
  private[data] def apply[F[_], S, E, A](
    unsafeRun: (Fold[Folded, S, E], Chain[E]) => F[Folded[(Chain[E], A)]]
  ): ActionT[F, S, E, A] = new ActionT(unsafeRun)
}

trait ActionTFunctions {
  def read[F[_]: Monad, S, E]: ActionT[F, S, E, S] =
    ActionT((f, es) => f.runFoldable(es).map((es, _)).pure[F])

  def append[F[_]: Applicative, S, E](e: NonEmptyChain[E]): ActionT[F, S, E, Unit] =
    ActionT((_, es0) => (es0 ++ e.toChain, ()).next.pure[F])

  def reset[F[_]: Applicative, S, E]: ActionT[F, S, E, Unit] =
    ActionT((_, _) => (Chain.empty[E], ()).next.pure[F])

  def liftF[F[_]: Functor, S, E, A](fa: F[A]): ActionT[F, S, E, A] =
    ActionT((_, es) => fa.map(a => (es, a).next))

  def pure[F[_]: Applicative, S, E, A](a: A): ActionT[F, S, E, A] =
    ActionT((_, es) => (es, a).next.pure[F])

  def sampleK[F[_]: Monad, S, E1, Env, E2](
    getEnv: F[Env]
  )(update: (Env, E1) => E2)(extract: E2 => E1): ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?] =
    new (ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?]) {
      override def apply[A](fa: ActionT[F, S, E1, A]): ActionT[F, S, E2, A] =
        fa.sample(getEnv)(update)(extract)
    }

  def xmapEventsK[F[_]: Functor, S, E1, E2, R](
    to: E1 => E2,
    from: E2 => E1
  ): ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?] =
    new (ActionT[F, S, E1, ?] ~> ActionT[F, S, E2, ?]) {
      override def apply[A](fa: ActionT[F, S, E1, A]): ActionT[F, S, E2, A] =
        fa.xmapEvents(to, from)
    }

  def expandK[F[_]: Functor, S1, S2, E](
    update: (S2, S1) => S2
  )(extract: S2 => S1): ActionT[F, S1, E, ?] ~> ActionT[F, S2, E, ?] =
    new (ActionT[F, S1, E, ?] ~> ActionT[F, S2, E, ?]) {
      override def apply[A](fa: ActionT[F, S1, E, A]): ActionT[F, S2, E, A] =
        fa.expand(update)(extract)
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
      ActionT { (fold, es) =>
        F.tailRecM((es, a)) {
          case (es, a) =>
            f(a).unsafeRun(fold, es).flatMap {
              case Next((es2, Left(nextA))) =>
                f(nextA).unsafeRun(fold, es2).map {
                  case Next((es, Left(a))) =>
                    (es, a).asLeft[Folded[(Chain[E], B)]]
                  case Next((es, Right(b))) =>
                    (es, b).next.asRight[(Chain[E], A)]
                  case Folded.Impossible =>
                    impossible[(Chain[E], B)].asRight[(Chain[E], A)]
                }
              case Next((es2, Right(b))) =>
                (es2, b).next.asRight[(Chain[E], A)].pure[F]
              case Impossible =>
                impossible[(Chain[E], B)].asRight[(Chain[E], A)].pure[F]
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
