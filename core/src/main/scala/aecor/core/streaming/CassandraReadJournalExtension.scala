package aecor.core.streaming

import java.util.UUID
import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.Committable
import akka.persistence.cassandra.query.UUIDEventEnvelope
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.datastax.driver.core.Session

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}

trait CommittableUUIDOffset extends Committable {
  def value: UUID
}

final case class CommittableUUIDOffsetImpl(override val value: UUID)(committer: UUID => Future[Done])
  extends CommittableUUIDOffset {

  override def commitScaladsl(): Future[Done] = committer(value)

  override def commitJavadsl(): CompletionStage[Done] = commitScaladsl().toJava
}


trait OffsetStore {
  def getOffset(tag: String, consumerId: String): Future[Option[UUID]]
  def setOffset(tag: String, consumerId: String, offset: UUID): Future[Unit]
}

class CassandraReadJournalExtension(actorSystem: ActorSystem, offsetStore: OffsetStore, readJournal: CassandraReadJournal) {

  implicit val ec: ExecutionContext = actorSystem.dispatcher

  val config = actorSystem.settings.config

  def committableEventsByTag(tag: String, consumerId: String): Source[CommittableJournalEntry[Any], NotUsed] = {
    Source.single(NotUsed).mapAsync(1) { _ =>
      offsetStore.getOffset(tag, consumerId)
    }.flatMapConcat { storedOffset =>
      val initialOffset = storedOffset.getOrElse(readJournal.firstOffset)
      val committer = { offset: UUID => offsetStore.setOffset(tag, consumerId, offset).map(_ => Done) }
      readJournal.eventsByTag(tag, initialOffset)
      .map { case UUIDEventEnvelope(offset, persistenceId, sequenceNr, event) =>
        CommittableJournalEntry(CommittableUUIDOffsetImpl(offset)(committer), persistenceId, sequenceNr, event)
      }
    }
  }
}

object CassandraReadJournalExtension {
  import aecor.util.cassandra._
  def init(keyspace: String)(implicit executionContext: ExecutionContext): Session => Future[_] = { session =>
    session.executeAsync(s"create table if not exists $keyspace.consumer_offset (consumer_id text, tag text, offset uuid, PRIMARY KEY ((consumer_id, tag)))")
  }
  def apply(actorSystem: ActorSystem, offsetStore: OffsetStore, readJournal: CassandraReadJournal)(implicit ec: ExecutionContext): CassandraReadJournalExtension =
    new CassandraReadJournalExtension(actorSystem, offsetStore, readJournal)
}
