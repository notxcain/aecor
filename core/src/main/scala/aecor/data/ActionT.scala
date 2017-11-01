package aecor.data

import cats.Applicative

import scala.collection.immutable._

final case class Action[S, E, A](run: S => (Seq[E], A))

final case class ActionT[F[_], State, Event, Result](run: State => F[(Seq[Event], Result)])
    extends AnyVal {
  def contramap[State1](f: State1 => State): ActionT[F, State1, Event, Result] =
    ActionT(run.compose(f))
}

object ActionT {
  final class MkLift[F[_], State] {
    def apply[E, A](f: State => (Seq[E], A))(implicit F: Applicative[F]): ActionT[F, State, E, A] =
      ActionT(s => F.pure(f(s)))
  }
  def lift[F[_], State]: MkLift[F, State] = new MkLift[F, State]
  def readOnly[F[_]: Applicative, State, Event, Result](
    f: State => Result
  ): ActionT[F, State, Event, Result] =
    ActionT(x => Applicative[F].pure((Seq.empty[Event], f(x))))
}
