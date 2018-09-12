package aecor.data.next

import aecor.data.Folded
import aecor.data.next.MonadActionRun.{ActionFailure, ActionResult}
import cats.data._
import cats.implicits._
import cats.{Applicative, Functor, Monad, MonadError, ~>}
import io.aecor.liberator.{Invocation, ReifiedInvocations}

final class ActionT[F[_], S, E, R, A] private (
  val unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[ActionResult[R, E, A]]
) extends AnyVal {

  def run(current: S, update: (S, E) => Folded[S]): F[ActionResult[R, E, A]] =
    unsafeRun(current, update, Chain.empty)

  def map[B](f: A => B)(implicit F: Functor[F]): ActionT[F, S, E, R, B] = ActionT {
    (s, u, ue) =>
      unsafeRun(s, u, ue).map(_.map(x => (x._1, f(x._2))))
  }

  def xmapState[S2](extract: S2 => S)(f: ((S2, E) => Folded[S2]) => (S, E) => Folded[S]): ActionT[F, S2, E, R, A] = ActionT {
    (s0: S2, u0: (S2, E) => Folded[S2], es0: Chain[E]) =>
      unsafeRun(extract(s0), f(u0), es0)
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
}

object ActionT {

  private def apply[F[_], S, E, R, A](unsafeRun: (S, (S, E) => Folded[S], Chain[E]) => F[ActionResult[R, E, A]]): ActionT[F, S, E, R, A] = new ActionT(unsafeRun)

  def read[F[_]: Applicative, S, E, R]: ActionT[F, S, E, R, S] =
    ActionT((s, _, es) => (es, s).asRight[ActionFailure[R]].pure[F])

  def append[F[_]: Applicative, S, E, R](e: NonEmptyChain[E]): ActionT[F, S, E, R, Unit] =
    ActionT((_, _, es0) => (es0 ++ e.toChain, ()).asRight[ActionFailure[R]].pure[F])

  def reject[F[_]: Applicative, S, E, R, A](r: R): ActionT[F, S, E, R, A] =
    ActionT(
      (_, _, _) => (ActionFailure.Rejection(r): ActionFailure[R]).asLeft[(Chain[E], A)].pure[F]
    )

  def reset[F[_]: Applicative, S, E, R]: ActionT[F, S, E, R, Unit] =
    ActionT(
      (_, _, _) => (Chain.empty[E], ()).asRight[ActionFailure[R]].pure[F]
    )

  def liftF[F[_]: Functor, S, E, R, A](fa: F[A]): ActionT[F, S, E, R, A] =
    ActionT((_, _, es) => fa.map(a => (es, a).asRight[ActionFailure[R]]))

  def pure[F[_]: Applicative, S, E, R, A](a: A): ActionT[F, S, E, R, A] =
    ActionT((_, _, es) => (es, a).asRight[ActionFailure[R]].pure[F])

  implicit def monadActionInstance[F[_], S, E, R](
    implicit F: Monad[F]
  ): MonadActionRun[ActionT[F, S, E, R, ?], F, S, E, R] =
    new MonadActionRun[ActionT[F, S, E, R, ?], F, S, E, R] {

      override def run[A](ma: ActionT[F, S, E, R, A])(
        current: S,
        update: (S, E) => Folded[S]
      ): F[ActionResult[R, E, A]] = ma.run(current, update)
      override def read: ActionT[F, S, E, R, S] = ActionT.read
      override def append(e: E, es: E*): ActionT[F, S, E, R, Unit] = ActionT.append(NonEmptyChain(e, es: _*))
      override def reject[A](r: R): ActionT[F, S, E, R, A] = ActionT.reject(r)
      override def liftF[A](fa: F[A]): ActionT[F, S, E, R, A] = ActionT.liftF(fa)

      override def map[A, B](fa: ActionT[F, S, E, R, A])(
        f: A => B
      ): ActionT[F, S, E, R, B] = fa.map(f)

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
                  ActionT.append[F, S, E, R](NonEmptyChain.fromChainUnsafe(c)).unsafeRun(s, ue, es).as(na.asLeft[ActionResult[R, E, B]])
                case _ =>
                  na.asLeft[ActionResult[R, E, B]].pure[F]
              }
          }
        }
      }
      override def pure[A](x: A): ActionT[F, S, E, R, A] = ActionT.pure(x)
      override def raiseError[A](e: R): ActionT[F, S, E, R, A] = reject(e)
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
}

trait MonadAction[M[_], S, E, R] extends MonadError[M, R] {
  def read: M[S]
  def append(es: E, other: E*): M[Unit]
  def reject[A](r: R): M[A]
}

trait MonadActionLift[M[_], F[_], S, E, R] extends MonadAction[M, S, E, R] {
  def liftF[A](fa: F[A]): M[A]
}

trait MonadActionRun[M[_], F[_], S, E, R] extends MonadActionLift[M, F, S, E, R] {
  def run[A](ma: M[A])(current: S, update: (S, E) => Folded[S]): F[ActionResult[R, E, A]]
}


object MonadActionRun {
  type ActionResult[+R, +E, +A] = Either[ActionFailure[R], (Chain[E], A)]
  sealed abstract class ActionFailure[+R]
  object ActionFailure {
    final case class Rejection[+R](a: R) extends ActionFailure[R]
    final case object ImpossibleFold extends ActionFailure[Nothing]
  }
}

object Counter {
  type State = Int
  type Event = String
  type Rejection = String

  def apply[F[_]: Monad](limit: Int): Counter[ActionT[F, State, Event, Rejection, ?]] =
    new Counter[ActionT[F, State, Event, Rejection, ?]](limit)


}

class Counter[F[_]](limit: Int)(implicit F: MonadAction[F, Counter.State, Counter.Event, Counter.Rejection]) {

  import F._

  def increment: F[Unit] =
    for {
      current <- read
      result <- if (current + 1 <= limit) {
                 append("Incremented")
               } else {
                 reject("Upper Limit Exceeded")
               }
    } yield result

  def decrement: F[Unit] =
    for {
      current <- read
      result <- if (current - 1 >= 0) {
                 append("Decremented")
               } else {
                 reject("Lower Limit Exceeded")
               }
    } yield result

  def incrementAndGet: F[Int] =
    increment >> read

  def decrementAndGet: F[Int] =
    decrement >> read
}

object State {

  def single[M[_[_]],  F[_], S, E, R](
    actions: M[ActionT[F, S, E, R, ?]],
    initialState: S,
    applyEvent: (S, E) => Folded[S]
  )(implicit F: MonadError[F, ActionFailure[R]],
    M: ReifiedInvocations[M]): M[StateT[F, Vector[E], ?]] =
    M.mapInvocations {
      new (Invocation[M, ?] ~> StateT[F, Vector[E], ?]) {
        override def apply[A](op: Invocation[M, A]): StateT[F, Vector[E], A] =
          for {
            events <- StateT.get[F, Vector[E]]
            result <- StateT
                       .liftF(op.invoke(actions).run(initialState, applyEvent))
                       .flatMap {
                         case Right((es, r)) =>
                           StateT
                             .set[F, Vector[E]](events ++ es.toVector)
                             .map(_ => r)

                         case Left(failure) =>
                           StateT.liftF[F, Vector[E], A](F.raiseError[A](failure))
                       }
          } yield result
      }
    }
}
