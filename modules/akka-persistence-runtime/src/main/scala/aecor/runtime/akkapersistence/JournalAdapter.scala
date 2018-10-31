package aecor.runtime.akkapersistence

import akka.persistence.query.Offset
import akka.persistence.query.scaladsl.{ CurrentEventsByTagQuery, EventsByTagQuery }

abstract class JournalAdapter[A] {
  abstract class OffsetAdapter {
    def unapply(arg: Offset): Option[A]
    def apply(value: Option[A]): Offset
  }
  def writeJournalId: String
  def createReadJournal: EventsByTagQuery with CurrentEventsByTagQuery
  val journalOffset: OffsetAdapter
}
