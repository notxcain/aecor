package aecor.data

import cats.Applicative
import cats.kernel.Monoid

final case class Handler[F[_], State, Change, A](run: State => F[(Change, A)]) extends AnyVal
object Handler {
  final class MkLift[F[_], State] {
    def apply[Change, A](
      f: State => (Change, A)
    )(implicit F: Applicative[F]): Handler[F, State, Change, A] =
      Handler(s => F.pure(f(s)))
  }
  def lift[F[_], State]: MkLift[F, State] = new MkLift[F, State]

  def inspect[F[_]: Applicative, State, Change: Monoid, A](
    f: State => A
  ): Handler[F, State, Change, A] =
    Handler(state => Applicative[F].pure((Monoid[Change].empty, f(state))))
}
