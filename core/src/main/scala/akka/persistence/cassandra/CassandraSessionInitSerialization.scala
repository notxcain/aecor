package akka.persistence.cassandra

import akka.Done
import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.event.Logging
import akka.persistence.cassandra.session.CassandraSessionSettings
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import com.datastax.driver.core.Session

import scala.concurrent.{ ExecutionContext, Future }

/**
  * DISCLAIMER:
  *
  * This object exposes private API from akka-persistence-cassandra.
  *
  * It could be broken at any next version.
  */
object CassandraSessionInitSerialization {

  /**
    * Exposes private CassandraSession#serializedExecution to run all schema mutation calls on single thread one by one
    * to avoid "Column family ID mismatch" exception in Cassandra.
    */
  def serialize(
    inits: (Session => Future[Unit])*
  )(implicit executionContext: ExecutionContext): Session => Future[Unit] = {
    def executeCreate: Session => Future[Unit] = { session =>
      def create(): Future[Unit] =
        inits.foldLeft(Future.successful(())) {
          case (x, init) => x.flatMap(_ => init(session))
        }
      CassandraSession
        .serializedExecution(
          recur = () => executeCreate(session).map(_ => Done),
          exec = () => create().map(_ => Done)
        )
        .map(_ => ())
    }
    executeCreate
  }
}

object DefaultJournalCassandraSession {

  /**
    * Creates CassandraSession using settings of default cassandra journal.
    *
    */
  def apply(system: ActorSystem, metricsCategory: String, init: Session => Future[Unit])(
    implicit executionContext: ExecutionContext
  ): CassandraSession = {
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
      executionContext,
      log,
      metricsCategory,
      init.andThen(_.map(_ => Done))
    )
  }
}
