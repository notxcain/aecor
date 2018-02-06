package aecor.data

import cats.{ Applicative, Functor, ~> }

import scala.collection.immutable._
import cats.implicits._
final case class Action[S, E, A](run: S => (Seq[E], A)) {
  def liftF[F[_]: Applicative]: ActionT[F, S, E, A] = ActionT.lift(this)
}

object Action {
  def read[S, E, A](f: S => A): Action[S, E, A] = Action(s => (Seq.empty[E], f(s)))
}

final case class ActionT[F[_], State, Event, Result](run: State => F[(Seq[Event], Result)])
    extends AnyVal {
  def mapS[State1](f: State1 => State): ActionT[F, State1, Event, Result] =
    ActionT(run.compose(f))
}

object ActionT {
  def lift[F[_]: Applicative, S, E, A](action: Action[S, E, A]): ActionT[F, S, E, A] =
    liftK[F, S, E].apply(action)

  def liftK[F[_]: Applicative, S, E]: Action[S, E, ?] ~> ActionT[F, S, E, ?] =
    new (Action[S, E, ?] ~> ActionT[F, S, E, ?]) {
      override def apply[A](action: Action[S, E, A]): ActionT[F, S, E, A] =
        ActionT { s =>
          action.run(s).pure[F]
        }
    }

  def enrich[F[_]: Functor, S, E, M](
    meta: F[M]
  ): Action[S, E, ?] ~> ActionT[F, S, Enriched[M, E], ?] =
    new (Action[S, E, ?] ~> ActionT[F, S, Enriched[M, E], ?]) {
      override def apply[A](action: Action[S, E, A]): ActionT[F, S, Enriched[M, E], A] =
        ActionT { s =>
          val (es, a) = action.run(s)
          meta.map { m =>
            (es.map(Enriched(m, _)), a)
          }
        }
    }
}
