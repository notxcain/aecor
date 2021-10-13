package akka.persistence.cassandra

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.Done
import akka.event.Logging
import akka.persistence.cassandra.session.CassandraSessionSettings
import akka.persistence.cassandra.Session.Init
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.functor._

object DefaultJournalCassandraSession {

  /** Creates CassandraSession using settings of default cassandra journal.
    */
  def apply[F[_]: Async](
      system: ActorSystem,
      metricsCategory: String,
      init: Init[F],
      sessionProvider: Option[SessionProvider] = None
  ): F[CassandraSession] =
    Dispatcher[F].allocated.map(_._1).map { dispatcher =>
      val log = Logging(system, classOf[CassandraSession])

      val provider = sessionProvider.getOrElse(
        SessionProvider(
          system.asInstanceOf[ExtendedActorSystem],
          system.settings.config.getConfig("cassandra-journal")
        )
      )

      val settings = CassandraSessionSettings(system.settings.config.getConfig("cassandra-journal"))

      new CassandraSession(
        system,
        provider,
        settings,
        system.dispatcher,
        log,
        metricsCategory,
        { x =>
          dispatcher.unsafeToFuture(init(Session[F](x)).as(Done))
        }
      )
    }
}
