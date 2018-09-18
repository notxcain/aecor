package aecor.tests

import aecor.data.next
import aecor.data.next.{ActionT, EventsourcedBehavior, MonadActionBase, MonadActionReject}
import aecor.tests.e2e.CounterEvent.{CounterDecremented, CounterIncremented}
import aecor.tests.e2e.{Counter, CounterEvent, CounterState}
import cats.Id
import org.scalatest.{FlatSpec, Matchers}
import cats.implicits._

class EventsourcedBehaviorTSpec extends FlatSpec with Matchers {

  class CounterOptionalActions[F[_]](implicit F: MonadActionBase[F, Option[CounterState], CounterEvent]) extends Counter[F] {

    import F._

    def increment: F[Long] = append(CounterIncremented) >> read.map(_.fold(0L)(_.value))

    def decrement: F[Long] = append(CounterDecremented) >> read.map(_.fold(0L)(_.value))

    def value: F[Long] = read.map(_.fold(0l)(_.value))
  }

  object CounterOptionalActions {
    def apply[F[_]](implicit F: MonadActionBase[F, Option[CounterState], CounterEvent]): Counter[F] =
      new CounterOptionalActions[F]
  }

  "EventsourcedBehavior.optional" should "correctly use init function applying events" in {
    val behavior: EventsourcedBehavior[Counter, Id, Option[CounterState], CounterEvent, Void] =
      EventsourcedBehavior
        .optional(CounterOptionalActions[ActionT[Id, Option[CounterState], CounterEvent, Void, ?]], e => CounterState(0L).applyEvent(e), _.applyEvent(_))

    behavior
      .update(behavior.initial, CounterEvent.CounterIncremented)
      .toOption
      .flatten shouldEqual CounterState(1).some
  }

}
