package aecor.data

import cats.Applicative

import scala.collection.immutable._

final case class Action[F[_], State, Event, Result](run: State => F[(Seq[Event], Result)])
    extends AnyVal {
  def contramap[State1](f: State1 => State): Action[F, State1, Event, Result] =
    Action(run.compose(f))
}

object Action {
  final class MkLift[F[_], State] {
    def apply[E, A](f: State => (Seq[E], A))(implicit F: Applicative[F]): Action[F, State, E, A] =
      Action(s => F.pure(f(s)))
  }
  def lift[F[_], State]: MkLift[F, State] = new MkLift[F, State]
  def readOnly[F[_]: Applicative, State, Event, Result](
    f: State => Result
  ): Action[F, State, Event, Result] =
    Action(x => Applicative[F].pure((Seq.empty[Event], f(x))))
}
