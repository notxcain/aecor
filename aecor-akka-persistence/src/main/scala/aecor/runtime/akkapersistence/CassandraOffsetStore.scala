package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data.TagConsumer
import aecor.effect.{ Async, CaptureFuture }
import aecor.util.KeyValueStore
import akka.persistence.cassandra._
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import com.datastax.driver.core.Session

import scala.concurrent.{ ExecutionContext, Future }

object CassandraOffsetStore {
  final case class Config(keyspace: String, tableName: String = "consumer_offset") {
    def createTableQuery: String =
      s"CREATE TABLE IF NOT EXISTS $keyspace.$tableName (consumer_id text, tag text, offset uuid, PRIMARY KEY ((consumer_id, tag)))"
    def updateOffsetQuery: String =
      s"UPDATE $keyspace.$tableName SET offset = ? where consumer_id = ? AND tag = ?"
    def selectOffsetQuery: String =
      s"SELECT offset FROM $keyspace.$tableName WHERE consumer_id = ? AND tag = ?"
  }

  def createTable(config: Config)(
    implicit executionContext: ExecutionContext
  ): Session => Future[Unit] = _.executeAsync(config.createTableQuery).asScala.map(_ => ())

  def apply[F[_]: Async: CaptureFuture](
    session: CassandraSession,
    config: CassandraOffsetStore.Config
  )(implicit executionContext: ExecutionContext): CassandraOffsetStore[F] =
    new CassandraOffsetStore(session, config)

}

class CassandraOffsetStore[F[_]: Async: CaptureFuture](
  session: CassandraSession,
  config: CassandraOffsetStore.Config
)(implicit executionContext: ExecutionContext)
    extends KeyValueStore[F, TagConsumer, UUID] {
  private val selectOffsetStatement =
    session.prepare(config.selectOffsetQuery)
  private val updateOffsetStatement =
    session.prepare(config.updateOffsetQuery)

  override def setValue(key: TagConsumer, value: UUID): F[Unit] =
    CaptureFuture[F].captureFuture {
      updateOffsetStatement
        .map { stmt =>
          stmt
            .bind()
            .setUUID("offset", value)
            .setString("tag", key.tag.value)
            .setString("consumer_id", key.consumerId.value)
        }
        .flatMap(session.executeWrite)
        .map(_ => ())
    }

  override def getValue(key: TagConsumer): F[Option[UUID]] = CaptureFuture[F].captureFuture {
    selectOffsetStatement
      .map(_.bind(key.consumerId.value, key.tag.value))
      .flatMap(session.selectOne)
      .map(_.map(_.getUUID("offset")))
  }
}
