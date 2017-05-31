package aecor.data

import cats.Applicative

final case class Handler[F[_], In, Effect, Out](run: In => F[(Effect, Out)]) extends AnyVal

object Handler {
  final class MkLift[F[_], State] {
    def apply[Effect, A](
      f: State => (Effect, A)
    )(implicit F: Applicative[F]): Handler[F, State, Effect, A] =
      Handler(s => F.pure(f(s)))
  }
  def lift[F[_], State]: MkLift[F, State] = new MkLift[F, State]

}
