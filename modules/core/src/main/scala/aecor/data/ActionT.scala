package aecor.data

import cats.implicits._
import cats.{ Applicative, FlatMap, ~> }

final case class ActionT[F[_], S, E, A](run: S => F[(List[E], A)]) extends AnyVal {
  def mapState[S1](f: S1 => S): ActionT[F, S1, E, A] =
    ActionT(run.compose(f))
  def mapK[G[_]](f: F ~> G): ActionT[G, S, E, A] = ActionT { s =>
    f(run(s))
  }
}

object ActionT {

  def mapK[F[_], G[_], S, E](f: F ~> G): ActionT[F, S, E, ?] ~> ActionT[G, S, E, ?] =
    new (ActionT[F, S, E, ?] ~> ActionT[G, S, E, ?]) {
      override def apply[A](fa: ActionT[F, S, E, A]): ActionT[G, S, E, A] = fa.mapK(f)
    }

  def liftK[F[_]: Applicative, S, E]: Action[S, E, ?] ~> ActionT[F, S, E, ?] =
    new (Action[S, E, ?] ~> ActionT[F, S, E, ?]) {
      override def apply[A](action: Action[S, E, A]): ActionT[F, S, E, A] =
        ActionT { s =>
          action.run(s).pure[F]
        }
    }

  def mapEventsF[F[_]: FlatMap, S, E, E1](
    f: List[E] => F[List[E1]]
  ): ActionT[F, S, E, ?] ~> ActionT[F, S, E1, ?] =
    new (ActionT[F, S, E, ?] ~> ActionT[F, S, E1, ?]) {
      override def apply[A](action: ActionT[F, S, E, A]): ActionT[F, S, E1, A] =
        ActionT { s =>
          action.run(s).flatMap {
            case (es, a) =>
              f(es).map((_, a))
          }
        }
    }
}
