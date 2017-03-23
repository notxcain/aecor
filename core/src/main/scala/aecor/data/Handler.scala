package aecor.data

import cats.data.Kleisli

final case class Handler[State, Change, A](run: State => (Change, A)) extends AnyVal {
  def asKleisli: Kleisli[(Change, ?), State, A] = Kleisli(run)
}
