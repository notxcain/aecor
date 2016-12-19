package aecor.core

import scala.collection.immutable.Seq

package object behavior {
  type Handler[State, Event, A] = State => (Seq[Event], A)
  object Handler {
    def apply[State, Event, A](f: State => (Seq[Event], A)): Handler[State, Event, A] = f
  }
}
