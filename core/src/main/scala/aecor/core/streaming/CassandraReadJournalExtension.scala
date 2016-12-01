package aecor.core.streaming

import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.Committable
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope2, Offset}
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}

trait CommittableOffset extends Committable {
  def value: Offset
}

final case class CommittableOffsetImpl(override val value: Offset)(
    committer: Offset => Future[Done])
    extends CommittableOffset {

  override def commitScaladsl(): Future[Done] = committer(value)

  override def commitJavadsl(): CompletionStage[Done] = commitScaladsl().toJava
}

trait OffsetStore {
  def getOffset(tag: String, consumerId: String): Future[Offset]
  def setOffset(tag: String, consumerId: String, offset: Offset): Future[Done]
}

class CassandraReadJournalExtension(actorSystem: ActorSystem,
                                    offsetStore: OffsetStore,
                                    readJournal: CassandraReadJournal)(
    implicit executionContext: ExecutionContext) {

  private val config = actorSystem.settings.config

  def committableEventsByTag(
      tag: String,
      consumerId: String): Source[CommittableJournalEntry[Any], NotUsed] = {
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getOffset(tag, consumerId)
      }
      .flatMapConcat { storedOffset =>
        readJournal.eventsByTag(tag, storedOffset).map {
          case EventEnvelope2(offset, persistenceId, sequenceNr, event) =>
            CommittableJournalEntry(
              CommittableOffsetImpl(offset)(
                offsetStore.setOffset(tag, consumerId, _)),
              persistenceId,
              sequenceNr,
              event)
        }
      }
  }
}

object CassandraReadJournalExtension {
  def apply(actorSystem: ActorSystem,
            offsetStore: OffsetStore,
            readJournal: CassandraReadJournal)(
      implicit ec: ExecutionContext): CassandraReadJournalExtension =
    new CassandraReadJournalExtension(actorSystem, offsetStore, readJournal)
}
