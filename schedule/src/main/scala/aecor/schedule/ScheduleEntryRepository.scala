package aecor.schedule

import java.time.LocalDateTime

import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future

trait ScheduleEntryRepository {
  def insertScheduleEntry(scheduleName: String,
                          scheduleBucket: String,
                          entryId: String,
                          dueDate: LocalDateTime): Future[Unit]
  def markScheduleEntryAsFired(scheduleName: String,
                               scheduleBucket: String,
                               entryId: String): Future[Unit]
  def getEntries(from: LocalDateTime, to: LocalDateTime): Source[ScheduleEntry, NotUsed]
}

object ScheduleEntryRepository {
  final case class ScheduleEntry(scheduleName: String,
                                 scheduleBucket: String,
                                 entryId: String,
                                 dueDate: LocalDateTime,
                                 timeBucket: String,
                                 fired: Boolean)
}
