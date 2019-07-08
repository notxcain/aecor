package akka.persistence.cassandra

import akka.Done
import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.event.Logging
import akka.persistence.cassandra.Session.Init
import akka.persistence.cassandra.session.CassandraSessionSettings
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import cats.effect.{ ContextShift, Effect }
import cats.implicits._

object DefaultJournalCassandraSession {

  /**
    * Creates CassandraSession using settings of default cassandra journal.
    *
    */
  def apply[F[_]: ContextShift](
    system: ActorSystem,
    metricsCategory: String,
    init: Init[F],
    sessionProvider: Option[SessionProvider] = None
  )(implicit F: Effect[F]): F[CassandraSession] = F.delay {
    val log = Logging(system, classOf[CassandraSession])
    val provider = sessionProvider.getOrElse(
      SessionProvider(
        system.asInstanceOf[ExtendedActorSystem],
        system.settings.config.getConfig("cassandra-journal")
      )
    )
    val settings = CassandraSessionSettings(system.settings.config.getConfig("cassandra-journal"))
    new CassandraSession(system, provider, settings, system.dispatcher, log, metricsCategory, { x =>
      F.toIO(init(Session[F](x)).as(Done)).unsafeToFuture()
    })
  }
}
