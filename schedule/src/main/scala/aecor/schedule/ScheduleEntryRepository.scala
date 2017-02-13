package aecor.schedule

import java.time.{ Clock, LocalDateTime }

import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

trait ScheduleEntryRepository {
  def insertScheduleEntry(scheduleName: String,
                          scheduleBucket: String,
                          entryId: String,
                          dueDate: LocalDateTime): Future[Unit]
  def getEntries(from: LocalDateTime, to: LocalDateTime): Source[ScheduleEntry, NotUsed]
  def dueEntries(from: LocalDateTime,
                 updateInterval: FiniteDuration,
                 clock: Clock): Source[ScheduleEntry, NotUsed] = {
    def rec(from: LocalDateTime): Source[ScheduleEntry, NotUsed] = {
      val to: LocalDateTime = LocalDateTime.now(clock)
      getEntries(from, to)
        .concat(
          Source
            .tick(updateInterval, updateInterval, ())
            .take(1)
            .flatMapConcat { _ =>
              rec(to)
            }
        )
    }
    rec(from)
  }
}

object ScheduleEntryRepository {
  final case class ScheduleEntry(scheduleName: String,
                                 scheduleBucket: String,
                                 entryId: String,
                                 dueDate: LocalDateTime,
                                 timeBucket: String)
}
