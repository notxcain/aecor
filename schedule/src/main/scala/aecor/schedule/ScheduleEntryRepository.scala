package aecor.schedule

import java.time.LocalDateTime

import aecor.schedule.ScheduleEntryRepository.ScheduleEntryView
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future

trait ScheduleEntryRepository {
  def insertScheduleEntry(scheduleName: String,
                          entryId: String,
                          dueDate: LocalDateTime): Future[Unit]
  def getEntries(since: LocalDateTime, till: LocalDateTime): Source[ScheduleEntryView, NotUsed]
}

object ScheduleEntryRepository {
  final case class ScheduleEntryView(scheduleName: String,
                                     entryId: String,
                                     dueDate: LocalDateTime,
                                     timeBucket: String)
}
