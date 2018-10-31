package aecor.runtime.akkapersistence

import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ NoOffset, Offset, PersistenceQuery, TimeBasedUUID }

final class CassandraJournalAdapter(system: ActorSystem,
                                    val writeJournalId: String,
                                    readJournalId: String)
    extends JournalAdapter[UUID] {

  override def createReadJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](readJournalId)

  override val journalOffset: OffsetAdapter = new OffsetAdapter {
    override def unapply(arg: Offset): Option[UUID] = arg match {
      case TimeBasedUUID(offsetValue) => Some(offsetValue)
      case _                          => None
    }
    override def apply(value: Option[UUID]): Offset = value match {
      case Some(x) => TimeBasedUUID(x)
      case None    => NoOffset
    }
  }
}

object CassandraJournalAdapter {
  val DefaultWriteJournalIdentifier: String = "cassandra-journal"
  def apply(system: ActorSystem,
            writeJournalId: String = DefaultWriteJournalIdentifier,
            readJournalId: String = CassandraReadJournal.Identifier): CassandraJournalAdapter =
    new CassandraJournalAdapter(system, writeJournalId, readJournalId)
}
