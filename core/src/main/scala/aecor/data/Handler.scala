package aecor.data

import scala.collection.immutable.Seq

final case class Handler[State, Event, +A](run: State => (Seq[Event], A)) extends AnyVal
