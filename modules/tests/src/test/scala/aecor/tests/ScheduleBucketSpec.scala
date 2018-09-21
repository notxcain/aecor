package aecor.tests

import java.time._

import aecor.data.{ ActionT, Folded}
import aecor.schedule.ScheduleEvent.{ScheduleEntryAdded, ScheduleEntryFired}
import aecor.schedule.{DefaultScheduleBucket, ScheduleEvent, ScheduleState}
import cats.Id
import cats.data.Chain
import org.scalatest.{FlatSpec, Matchers}

class ScheduleBucketSpec extends FlatSpec with Matchers with StrictCatsEquality {
  val clock = Clock.fixed(Instant.now, ZoneId.systemDefault())
  val aggregate = DefaultScheduleBucket[ActionT[Id, ScheduleState, ScheduleEvent, ?], Id](ZonedDateTime.now(clock))

  "ScheduleBucket" should "fire entry when due date is before now" in {
    val handler = aggregate.addScheduleEntry(
      "entryId",
      "correlation",
      LocalDateTime.now(clock).minusSeconds(10)
    )

    val Folded.Next((events, _)) = handler.run(ScheduleState.initial, _.update(_))
    assert(events ===
      Chain(
        ScheduleEntryAdded(
          "entryId",
          "correlation",
          LocalDateTime.now(clock).minusSeconds(10),
          Instant.now(clock)
        ),
        ScheduleEntryFired("entryId", "correlation", Instant.now(clock))
      ))
  }
  it should "not fire entry when due date is after now" in {
    val handler =
      aggregate.addScheduleEntry("entryId", "correlation", LocalDateTime.now(clock).plusSeconds(10))

    val Folded.Next((events, _)) = handler.run(ScheduleState.initial, _.update(_))
    assert(events ===
      Chain(
        ScheduleEntryAdded(
          "entryId",
          "correlation",
          LocalDateTime.now(clock).plusSeconds(10),
          Instant.now(clock)
        )
      )
    )
  }
}
