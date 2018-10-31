package akka.persistence.cassandra

import akka.Done
import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.event.Logging
import akka.persistence.cassandra.session.CassandraSessionSettings
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import cats.effect.Effect
import cats.implicits._

object DefaultJournalCassandraSession {

  /**
    * Creates CassandraSession using settings of default cassandra journal.
    *
    */
  def apply[F[_]](system: ActorSystem, metricsCategory: String, init: Session[F] => F[Unit])(
    implicit F: Effect[F]
  ): F[CassandraSession] = F.delay {
    val log = Logging(system, classOf[CassandraSession])
    val provider = SessionProvider(
      system.asInstanceOf[ExtendedActorSystem],
      system.settings.config.getConfig("cassandra-journal")
    )
    val settings = CassandraSessionSettings(system.settings.config.getConfig("cassandra-journal"))
    new CassandraSession(system, provider, settings, system.dispatcher, log, metricsCategory, { x =>
      F.toIO(init(Session[F](x)).as(Done)).unsafeToFuture()
    })
  }
}
