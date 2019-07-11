package aecor.tests

import java.time._

import aecor.data.Folded
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.{ DefaultScheduleBucket, ScheduleState }
import cats.Id
import cats.data.Chain
import org.scalatest.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class ScheduleBucketSpec extends AnyFlatSpec with Matchers with StrictCatsEquality {
  val clock = Clock.fixed(Instant.now, ZoneId.systemDefault())
  val aggregate = DefaultScheduleBucket.behavior[Id](ZonedDateTime.now(clock)).actions

  "ScheduleBucket" should "fire entry when due date is before now" in {
    val handler = aggregate.addScheduleEntry(
      "entryId",
      "correlation",
      LocalDateTime.now(clock).minusSeconds(10)
    )

    val Folded.Next((events, _)) = handler.run(ScheduleState.fold)
    assert(
      events ===
        Chain(
          ScheduleEntryAdded(
            "entryId",
            "correlation",
            LocalDateTime.now(clock).minusSeconds(10),
            Instant.now(clock)
          ),
          ScheduleEntryFired("entryId", "correlation", Instant.now(clock))
        )
    )
  }
  it should "not fire entry when due date is after now" in {
    val handler =
      aggregate.addScheduleEntry("entryId", "correlation", LocalDateTime.now(clock).plusSeconds(10))

    val Folded.Next((events, _)) = handler.run(ScheduleState.fold)
    assert(
      events ===
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
