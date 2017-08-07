package aecor.tests

import java.time._

import scala.collection.immutable._
import aecor.schedule.{ DefaultScheduleAggregate, ScheduleEvent, ScheduleState }
import cats.Id
import org.scalatest.{ FlatSpec, Matchers }

class ScheduleAggregateSpec extends FlatSpec with Matchers {
  val clock = Clock.fixed(Instant.now, ZoneId.systemDefault())
  val aggregate = DefaultScheduleAggregate[Id](ZonedDateTime.now(clock))

  "ScheduleAggregate" should "fire entry when due date is before now" in {
    val handler = aggregate.addScheduleEntry(
      "name",
      "bucket",
      "entryId",
      "correlation",
      LocalDateTime.now(clock).minusSeconds(10)
    )

    val (events, reply) = handler.run(ScheduleState.initial)
    events.shouldEqual(
      Seq(
        ScheduleEvent.ScheduleEntryAdded(
          "name",
          "bucket",
          "entryId",
          "correlation",
          LocalDateTime.now(clock).minusSeconds(10),
          Instant.now(clock)
        ),
        ScheduleEvent
          .ScheduleEntryFired("name", "bucket", "entryId", "correlation", Instant.now(clock))
      )
    )
  }
  it should "not fire entry when due date is after now" in {
    val handler = aggregate.addScheduleEntry(
      "name",
      "bucket",
      "entryId",
      "correlation",
      LocalDateTime.now(clock).plusSeconds(10)
    )

    val (events, reply) = handler.run(ScheduleState.initial)
    events.shouldEqual(
      Seq(
        ScheduleEvent.ScheduleEntryAdded(
          "name",
          "bucket",
          "entryId",
          "correlation",
          LocalDateTime.now(clock).plusSeconds(10),
          Instant.now(clock)
        )
      )
    )
  }
}
