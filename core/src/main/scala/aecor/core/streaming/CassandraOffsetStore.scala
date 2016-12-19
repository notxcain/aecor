package aecor.core.streaming

import java.util.UUID

import akka.Done
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import com.datastax.driver.core.Session

import scala.concurrent.{ExecutionContext, Future}

object CassandraOffsetStore {
  case class Config(keyspace: String, tableName: String = "consumer_offset") {
    def createTableQuery: String =
      s"CREATE TABLE IF NOT EXISTS $keyspace.$tableName (consumer_id text, tag text, offset uuid, PRIMARY KEY ((consumer_id, tag)))"
    def updateOffsetQuery: String =
      s"UPDATE $keyspace.$tableName SET offset = ? where consumer_id = ? AND tag = ?"
    def selectOffsetQuery: String =
      s"SELECT offset FROM $keyspace.$tableName WHERE consumer_id = ? AND tag = ?"
  }

  def initSchema(config: Config)(
      implicit executionContext: ExecutionContext): Session => Future[Done] = {
    session =>
      import aecor.util.cassandra._
      session.executeAsync(config.createTableQuery).map(_ => Done)
  }
}

class CassandraOffsetStore(sessionWrapper: CassandraSession,
                           queries: CassandraOffsetStore.Config)(
    implicit executionContext: ExecutionContext)
    extends OffsetStore[UUID] {

  private val selectOffsetStatement =
    sessionWrapper.prepare(queries.selectOffsetQuery)
  private val updateOffsetStatement =
    sessionWrapper.prepare(queries.updateOffsetQuery)
  override def getOffset(tag: String,
                         consumerId: String): Future[Option[UUID]] =
    selectOffsetStatement
      .map(_.bind(consumerId, tag))
      .flatMap(sessionWrapper.selectOne)
      .map(_.map(_.getUUID("offset")))

  override def setOffset(tag: String,
                         consumerId: String,
                         offset: UUID): Future[Done] =
    updateOffsetStatement.map { stmt =>
      stmt
        .bind()
        .setUUID("offset", offset)
        .setString("tag", tag)
        .setString("consumer_id", consumerId)
    }.flatMap(sessionWrapper.executeWrite)

}
