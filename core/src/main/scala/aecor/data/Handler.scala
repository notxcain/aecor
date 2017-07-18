package aecor.data

import cats.Applicative

import scala.collection.immutable._

final case class Handler[F[_], State, Event, Result](run: State => F[(Seq[Event], Result)])
    extends AnyVal

object Handler {
  final class MkLift[F[_], State] {
    def apply[E, A](f: State => (Seq[E], A))(implicit F: Applicative[F]): Handler[F, State, E, A] =
      Handler(s => F.pure(f(s)))
  }
  def lift[F[_], State]: MkLift[F, State] = new MkLift[F, State]
  def readOnly[F[_]: Applicative, State, Event, Result](
    f: State => Result
  ): Handler[F, State, Event, Result] =
    Handler(x => Applicative[F].pure((Seq.empty[Event], f(x))))
}
