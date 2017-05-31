package aecor.data

import cats.Applicative
import scala.collection.immutable._

final case class Handler[F[_], In, Event, Out](run: In => F[(Seq[Event], Out)]) extends AnyVal

object Handler {
  final class MkLift[F[_], State] {
    def apply[E, A](f: State => (Seq[E], A))(implicit F: Applicative[F]): Handler[F, State, E, A] =
      Handler(s => F.pure(f(s)))
  }
  def lift[F[_], State]: MkLift[F, State] = new MkLift[F, State]

}
