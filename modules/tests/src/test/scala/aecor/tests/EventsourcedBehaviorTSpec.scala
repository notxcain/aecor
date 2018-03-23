package aecor.tests

import aecor.data.{ Action, EventsourcedBehavior }
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.{ Counter, CounterEvent, CounterState }
import org.scalatest.{ FlatSpec, Matchers }
import cats.implicits._

class EventsourcedBehaviorTSpec extends FlatSpec with Matchers {

  object CounterOptionalActions extends Counter[Action[Option[CounterState], CounterEvent, ?]] {

    def increment: Action[Option[CounterState], CounterEvent, Long] = Action { x =>
      List(CounterIncremented) -> (x.map(_.value).getOrElse(0L) + 1)
    }

    def decrement: Action[Option[CounterState], CounterEvent, Long] = Action { x =>
      List(CounterDecremented) -> (x.map(_.value).getOrElse(0L) - 1)
    }

    def value: Action[Option[CounterState], CounterEvent, Long] =
      Action(x => List.empty -> x.map(_.value).getOrElse(0))
  }

  "EventsourcedBehavior.optional" should "correctly use init function applying events" in {
    val behavior: EventsourcedBehavior[Counter, Option[CounterState], CounterEvent] =
      EventsourcedBehavior
        .optional(CounterOptionalActions, (e) => CounterState(0L).applyEvent(e), _.applyEvent(_))

    behavior
      .applyEvent(behavior.initialState, CounterEvent.CounterIncremented)
      .toOption
      .flatten shouldEqual CounterState(1).some
  }

}
