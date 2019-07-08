package aecor.old.data

import cats.data.Kleisli

import scala.collection.immutable.Seq

final case class Handler[State, Event, A](run: State => (Seq[Event], A)) extends AnyVal {
  def asKleisli: Kleisli[(Seq[Event], ?), State, A] = Kleisli(run)
}
