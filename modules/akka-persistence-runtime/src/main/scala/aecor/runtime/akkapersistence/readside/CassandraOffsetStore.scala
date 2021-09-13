package aecor.runtime.akkapersistence.readside

import java.util.UUID

import aecor.data.TagConsumer
import aecor.runtime.KeyValueStore
import akka.persistence.cassandra.Session.Init
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import cats.data.Kleisli
import cats.effect.{ IO, LiftIO }
import cats.syntax.all._
import cats.{ Functor, Monad }

object CassandraOffsetStore {
  final case class Queries(keyspace: String, tableName: String = "consumer_offset") {
    def createTableQuery: String =
      s"CREATE TABLE IF NOT EXISTS $keyspace.$tableName (consumer_id text, tag text, offset uuid, PRIMARY KEY ((consumer_id, tag)))"
    def updateOffsetQuery: String =
      s"UPDATE $keyspace.$tableName SET offset = ? where consumer_id = ? AND tag = ?"
    def deleteOffsetQuery: String =
      s"DELETE FROM $keyspace.$tableName where consumer_id = ? AND tag = ?"
    def selectOffsetQuery: String =
      s"SELECT offset FROM $keyspace.$tableName WHERE consumer_id = ? AND tag = ?"
  }

  def apply[F[_]]: Builder[F] = builderInstance.asInstanceOf[Builder[F]]

  private val builderInstance = new Builder[Any]()

  final class Builder[F[_]] private[CassandraOffsetStore] () {
    def createTable(config: Queries)(implicit F: Functor[F]): Init[F] =
      Kleisli(_.execute(config.createTableQuery).void)

    def apply(
      session: CassandraSession,
      config: CassandraOffsetStore.Queries
    )(implicit F: Monad[F], L: LiftIO[F]): CassandraOffsetStore[F] =
      new CassandraOffsetStore(session, config)
  }
}

class CassandraOffsetStore[F[_]: Monad] private[akkapersistence] (
  session: CassandraSession,
  config: CassandraOffsetStore.Queries
)(implicit F: LiftIO[F])
    extends KeyValueStore[F, TagConsumer, UUID] {

  private val selectOffsetStatement =
    session.prepare(config.selectOffsetQuery)

  private val updateOffsetStatement =
    session.prepare(config.updateOffsetQuery)

  private val deleteOffsetStatement =
    session.prepare(config.deleteOffsetQuery)

  override def setValue(key: TagConsumer, value: UUID): F[Unit] =
    IO.fromFuture(IO(updateOffsetStatement))
      .map { stmt =>
        stmt
          .bind()
          .setUUID("offset", value)
          .setString("tag", key.tag.value)
          .setString("consumer_id", key.consumerId.value)
      }
      .flatMap(x => IO.fromFuture(IO(session.executeWrite(x))))
      .to[F]
      .void

  override def getValue(key: TagConsumer): F[Option[UUID]] =
    IO.fromFuture(IO(selectOffsetStatement))
      .map(_.bind(key.consumerId.value, key.tag.value))
      .flatMap(x => IO.fromFuture(IO(session.selectOne(x))))
      .to[F]
      .map(_.map(_.getUUID("offset")))

  override def deleteValue(key: TagConsumer): F[Unit] =
    IO.fromFuture(IO(deleteOffsetStatement))
      .map(_.bind(key.consumerId.value, key.tag.value))
      .flatMap(x => IO.fromFuture(IO(session.executeWrite(x))))
      .to[F]
      .void
}
