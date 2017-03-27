package aecor.data

import cats.Applicative
import cats.kernel.Monoid

final case class Handler[F[_], State, Change, A](run: State => F[(Change, A)]) extends AnyVal
object Handler {
  def lift[F[_]: Applicative, State, Change, A](
    f: State => (Change, A)
  ): Handler[F, State, Change, A] =
    Handler(s => Applicative[F].pure(f(s)))

  def inspect[F[_]: Applicative, State, Change: Monoid, A](
    f: State => A
  ): Handler[F, State, Change, A] =
    Handler(state => Applicative[F].pure((Monoid[Change].empty, f(state))))
}
