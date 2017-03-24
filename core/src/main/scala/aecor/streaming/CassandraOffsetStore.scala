package aecor.streaming

import java.util.UUID

import aecor.aggregate.runtime.{ Async, CaptureFuture }
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import com.datastax.driver.core.Session
import akka.persistence.cassandra._

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
    extends OffsetStore[F, UUID] {
  private val selectOffsetStatement =
    session.prepare(config.selectOffsetQuery)
  private val updateOffsetStatement =
    session.prepare(config.updateOffsetQuery)

  override def getOffset(tag: String, consumerId: ConsumerId): F[Option[UUID]] =
    CaptureFuture[F].captureF {
      selectOffsetStatement
        .map(_.bind(consumerId.value, tag))
        .flatMap(session.selectOne)
        .map(_.map(_.getUUID("offset")))
    }

  override def setOffset(tag: String, consumerId: ConsumerId, offset: UUID): F[Unit] =
    CaptureFuture[F].captureF {
      updateOffsetStatement
        .map { stmt =>
          stmt
            .bind()
            .setUUID("offset", offset)
            .setString("tag", tag)
            .setString("consumer_id", consumerId.value)
        }
        .flatMap(session.executeWrite)
        .map(_ => ())
    }
}
