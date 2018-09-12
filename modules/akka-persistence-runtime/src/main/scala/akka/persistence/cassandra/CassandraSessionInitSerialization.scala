package akka.persistence.cassandra

import akka.Done
import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.event.Logging
import akka.persistence.cassandra.session.CassandraSessionSettings
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import cats.Monad
import cats.effect.Effect
import cats.implicits._

object CassandraSessionInitSerialization {

  /**
    * Exposes private CassandraSession#serializedExecution to run all schema mutation calls on single thread one by one
    * to avoid "Column family ID mismatch" exception in Cassandra.
    */
  def serialize[F[_]: Monad](inits: (Session[F] => F[Unit])*): Session[F] => F[Unit] = { session =>
    inits.foldLeft(().pure[F]) { (acc, init) =>
       acc >> init(session)
    }
  }
}

object DefaultJournalCassandraSession {

  /**
    * Creates CassandraSession using settings of default cassandra journal.
    *
    */
  def apply[F[_]](system: ActorSystem, metricsCategory: String, init: Session[F] => F[Unit])(implicit F: Effect[F]): F[CassandraSession] = F.delay {
    val log = Logging(system, classOf[CassandraSession])
    val provider = SessionProvider(
      system.asInstanceOf[ExtendedActorSystem],
      system.settings.config.getConfig("cassandra-journal")
    )
    val settings = CassandraSessionSettings(system.settings.config.getConfig("cassandra-journal"))
    new CassandraSession(
      system,
      provider,
      settings,
      system.dispatcher,
      log,
      metricsCategory,
      {x => F.toIO(init(Session[F](x)).as(Done)).unsafeToFuture()}
    )
  }
}
