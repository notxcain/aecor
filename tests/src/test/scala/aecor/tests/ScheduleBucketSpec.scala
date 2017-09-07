package aecor.tests

import java.time._

import scala.collection.immutable._
import aecor.schedule.{ DefaultScheduleBucket, ScheduleEvent, ScheduleState }
import cats.Id
import org.scalatest.{ FlatSpec, Matchers }

class ScheduleBucketSpec extends FlatSpec with Matchers {
  val clock = Clock.fixed(Instant.now, ZoneId.systemDefault())
  val aggregate = DefaultScheduleBucket[Id](ZonedDateTime.now(clock))

  "ScheduleAggregate" should "fire entry when due date is before now" in {
    val handler = aggregate.addScheduleEntry(
      "entryId",
      "correlation",
      LocalDateTime.now(clock).minusSeconds(10)
    )

    val (events, _) = handler.run(ScheduleState.initial)
    events.shouldEqual(
      Seq(
        ScheduleEvent.ScheduleEntryAdded(
          "entryId",
          "correlation",
          LocalDateTime.now(clock).minusSeconds(10),
          Instant.now(clock)
        ),
        ScheduleEvent
          .ScheduleEntryFired("entryId", "correlation", Instant.now(clock))
      )
    )
  }
  it should "not fire entry when due date is after now" in {
    val handler =
      aggregate.addScheduleEntry("entryId", "correlation", LocalDateTime.now(clock).plusSeconds(10))

    val (events, _) = handler.run(ScheduleState.initial)
    events.shouldEqual(
      Seq(
        ScheduleEvent.ScheduleEntryAdded(
          "entryId",
          "correlation",
          LocalDateTime.now(clock).plusSeconds(10),
          Instant.now(clock)
        )
      )
    )
  }
}
