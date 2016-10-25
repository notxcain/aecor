package aecor.core.streaming

import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import com.datastax.driver.core.Session

import scala.concurrent.{ExecutionContext, Future}

object CassandraOffsetStore {
  class Queries(actorSystem: ActorSystem) {
    val config = actorSystem.settings.config
    val keyspace = config.getString("cassandra-journal.keyspace")
    def createTableQuery = s"CREATE TABLE IF NOT EXISTS $keyspace.consumer_offset (consumer_id text, tag text, offset uuid, PRIMARY KEY ((consumer_id, tag)))"
    def updateOffsetQuery = s"UPDATE $keyspace.consumer_offset SET offset = ? where consumer_id = ? AND tag = ?"
    def selectOffsetQuery = s"select offset from $keyspace.consumer_offset where consumer_id = ? and tag = ?"
    def init(implicit executionContext: ExecutionContext): Session => Future[Done] = { session =>
      import aecor.util.cassandra._
      session.executeAsync(createTableQuery).map(_ => Done)
    }
  }
}

class CassandraOffsetStore(sessionWrapper: CassandraSession, queries: CassandraOffsetStore.Queries)(implicit executionContext: ExecutionContext) extends OffsetStore {

  private val selectOffsetStatement = sessionWrapper.prepare(queries.selectOffsetQuery)
  private val updateOffsetStatement = sessionWrapper.prepare(queries.updateOffsetQuery)
  override def getOffset(tag: String, consumerId: String): Future[Option[UUID]] =
    selectOffsetStatement
    .map(_.bind(consumerId, tag))
    .flatMap(sessionWrapper.selectOne)
    .map(_.map(_.getUUID("offset")))


  override def setOffset(tag: String, consumerId: String, offset: UUID): Future[Unit] =
    updateOffsetStatement.flatMap(stmt => sessionWrapper.executeWrite(stmt.bind(offset, consumerId, tag))).map(_ => ())

}
