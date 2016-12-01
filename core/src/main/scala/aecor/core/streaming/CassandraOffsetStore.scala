package aecor.core.streaming

import akka.Done
import akka.actor.ActorSystem
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import com.datastax.driver.core.Session

import scala.concurrent.{ExecutionContext, Future}

object CassandraOffsetStore {
  class Queries(actorSystem: ActorSystem) {
    val config = actorSystem.settings.config
    val keyspace = config.getString("cassandra-journal.keyspace")
    def createTableQuery =
      s"CREATE TABLE IF NOT EXISTS $keyspace.consumer_offset (consumer_id text, tag text, offset uuid, sequence_offset long, PRIMARY KEY ((consumer_id, tag)))"
    def updateOffsetQuery =
      s"UPDATE $keyspace.consumer_offset SET offset = ?, sequence_offset = ? where consumer_id = ? AND tag = ?"
    def selectOffsetQuery =
      s"select offset, sequence_offset from $keyspace.consumer_offset where consumer_id = ? and tag = ?"
    def init(implicit executionContext: ExecutionContext)
      : Session => Future[Done] = { session =>
      import aecor.util.cassandra._
      session.executeAsync(createTableQuery).map(_ => Done)
    }
  }
}

class CassandraOffsetStore(sessionWrapper: CassandraSession,
                           queries: CassandraOffsetStore.Queries)(
    implicit executionContext: ExecutionContext)
    extends OffsetStore {

  private val selectOffsetStatement =
    sessionWrapper.prepare(queries.selectOffsetQuery)
  private val updateOffsetStatement =
    sessionWrapper.prepare(queries.updateOffsetQuery)
  override def getOffset(tag: String, consumerId: String): Future[Offset] =
    selectOffsetStatement
      .map(_.bind(consumerId, tag))
      .flatMap(sessionWrapper.selectOne)
      .map {
        case Some(row) =>
          val uuid = row.getUUID("offset")
          if (uuid != null) {
            TimeBasedUUID(uuid)
          } else {
            if (row.isNull("sequence_offset")) {
              NoOffset
            } else {
              Sequence(row.getLong("sequence_offset"))
            }
          }
        case None =>
          NoOffset
      }

  override def setOffset(tag: String,
                         consumerId: String,
                         offset: Offset): Future[Done] =
    updateOffsetStatement.map { stmt =>
      offset match {
        case Sequence(value) =>
          stmt.bind(null, java.lang.Long.valueOf(value), consumerId, tag)
        case TimeBasedUUID(value) =>
          stmt.bind(value, null, consumerId, tag)
        case NoOffset =>
          stmt.bind(null, null, consumerId, tag)
        case other =>
          throw new IllegalArgumentException(
            s"Cassandra offset store does not support [$other]")
      }
    }.flatMap(sessionWrapper.executeWrite)

}
