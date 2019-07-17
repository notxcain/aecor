package aecor.tests

import aecor.data._
import aecor.tests.e2e.{ CounterEvent, CounterState }
import cats.implicits._
import org.scalatest.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class EventsourcedBehaviorSpec extends AnyFlatSpec with Matchers {

  "EventsourcedBehavior.optional" should "correctly use init function applying events" in {
    val behavior: Fold[Folded, Option[CounterState], CounterEvent] =
      Fold.optional(e => CounterState(0L).applyEvent(e))(_.applyEvent(_))
    behavior
      .reduce(behavior.initial, CounterEvent.CounterIncremented)
      .toOption
      .flatten shouldEqual CounterState(1).some
  }

}
