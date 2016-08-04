package aecor.core.streaming

import java.util.UUID
import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.Committable
import akka.persistence.cassandra.query.UUIDEventEnvelope
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}

trait CommittableUUIDOffset extends Committable {
  def offset: UUID
}

final case class CommittableUUIDOffsetImpl(override val offset: UUID)(committer: UUID => Future[Done])
  extends CommittableUUIDOffset {
  override def commitScaladsl(): Future[Done] = committer(offset)

  override def commitJavadsl(): CompletionStage[Done] = commitScaladsl().toJava
}

case class CommittableJournalEntry[+A](committableOffset: CommittableUUIDOffset, persistenceId: String, sequenceNr: Long, value: A)

class CassandraReadJournalExtension(actorSystem: ActorSystem, readJournal: CassandraReadJournal)(implicit ec: ExecutionContext) {

  def underlying: CassandraReadJournal = readJournal

  val config = actorSystem.settings.config

  val keyspace = config.getString("cassandra-journal.keyspace")

  private val createTableStatement = readJournal.session
                                     .prepare(s"create table if not exists $keyspace.consumer_offset (consumer_id text, tag text, offset uuid, PRIMARY KEY ((consumer_id, tag)))")

  private val selectOffsetStatement = readJournal.session.prepare(s"select offset from $keyspace.consumer_offset where consumer_id = ? and tag = ?")
  private val updateOffsetStatement = readJournal.session.prepare(s"update $keyspace.consumer_offset set offset = ? where consumer_id = ? and tag = ?")

  def committableEventsByTag(tag: String, consumerId: String): Source[CommittableJournalEntry[Any], NotUsed] = {
    Source.single(NotUsed).mapAsync(1) { _ =>
      createTableStatement.map(_.bind()).flatMap(readJournal.session.executeWrite).flatMap { _ =>
        updateOffsetStatement.flatMap { updateStatement =>
          selectOffsetStatement
          .map(_.bind(consumerId, tag))
          .flatMap(readJournal.session.select)
          .map { rs =>
            Option(rs.one())
            .map(_.getUUID("offset"))
            .getOrElse(readJournal.firstOffset)
          }
          .map { initialOffset =>
            (initialOffset, { offset: UUID => readJournal.session.executeWrite(updateStatement.bind(offset, consumerId, tag)).map(_ => Done) })
          }
        }
      }
    }.flatMapConcat { case (initialOffset, committer) =>
      readJournal.eventsByTag(tag, initialOffset)
      .map { case UUIDEventEnvelope(offset, persistenceId, sequenceNr, event) =>
        CommittableJournalEntry(CommittableUUIDOffsetImpl(offset)(committer), persistenceId, sequenceNr, event)
      }
    }
  }
}

object CassandraReadJournalExtension {
  def apply(actorSystem: ActorSystem, readJournal: CassandraReadJournal)(implicit ec: ExecutionContext): CassandraReadJournalExtension = new CassandraReadJournalExtension(actorSystem, readJournal)
}
