package aecor.core.streaming

import java.util.concurrent.CompletionStage

import akka.actor.ActorSystem
import akka.{Done, NotUsed}
import akka.kafka.ConsumerMessage.Committable
import akka.persistence.cassandra.query.UUIDEventEnvelope
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.stream.scaladsl.Source

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}

class ExtendedCassandraReadJournal(actorSystem: ActorSystem, readJournal: CassandraReadJournal) {

  def underlying: CassandraReadJournal = readJournal

  val config = actorSystem.settings.config

  val keyspace = config.getString("cassandra-journal.keyspace")

  private val createTable = readJournal.session
    .prepare(s"create table if not exists $keyspace.consumer_offset (consumer_id text, tag text, offset uuid, PRIMARY KEY ((consumer_id, tag)))")
    .flatMap(x => readJournal.session.executeWrite(x.bind()))(actorSystem.dispatcher)

  createTable.onComplete {
    x => println(x)
  } (actorSystem.dispatcher)

  private val selectOffsetStatement = readJournal.session.prepare(s"select offset from $keyspace.consumer_offset where consumer_id = ? and tag = ?")
  private val updateOffsetStatement = readJournal.session.prepare(s"update $keyspace.consumer_offset set offset = ? where consumer_id = ? and tag = ?")

  def committableEventsByTag(tag: String, consumerId: String)(implicit ec: ExecutionContext): Source[CommittableMessage[UUIDEventEnvelope], NotUsed] = {
    Source.single(NotUsed).mapAsync(1) { _ =>
      createTable.flatMap { _ =>
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
              (initialOffset, updateStatement)
            }
        }
      }
    }.flatMapConcat { case (initialOffset, updateStatement) =>
      readJournal.eventsByTag(tag, initialOffset)
        .map { e =>
          val committable = new Committable {
            override def commitScaladsl(): Future[Done] =
              readJournal.session.executeWrite(updateStatement.bind(e.offset, consumerId, tag)).map(_ => Done)
            override def commitJavadsl(): CompletionStage[Done] =
              commitScaladsl().toJava
          }
          CommittableMessage(committable, e)
        }
    }
  }
}

object ExtendedCassandraReadJournal {
  def apply(actorSystem: ActorSystem, readJournal: CassandraReadJournal): ExtendedCassandraReadJournal = new ExtendedCassandraReadJournal(actorSystem, readJournal)
}
