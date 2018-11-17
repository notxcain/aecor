package aecor.tests

import aecor.MonadAction
import aecor.data._
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.{ Counter, CounterEvent, CounterState }
import cats.{ Id, Monad }
import org.scalatest.{ FlatSpec, Matchers }
import cats.implicits._

class EventsourcedBehaviorSpec extends FlatSpec with Matchers {

  class CounterOptionalActions[F[_]: Monad](
    implicit F: MonadAction[F, Option[CounterState], CounterEvent]
  ) extends Counter[F] {

    import F._

    def increment: F[Long] = append(CounterIncremented) >> value

    def decrement: F[Long] = append(CounterDecremented) >> value

    def value: F[Long] = read.map(_.fold(0l)(_.value))
  }

  "EventsourcedBehavior.optional" should "correctly use init function applying events" in {
    val behavior: EventsourcedBehavior[Counter, Id, Option[CounterState], CounterEvent] =
      EventsourcedBehavior
        .optional(new CounterOptionalActions, e => CounterState(0L).applyEvent(e), _.applyEvent(_))
    behavior
      .update(behavior.create, CounterEvent.CounterIncremented)
      .toOption
      .flatten shouldEqual CounterState(1).some
  }

}
