package akka.persistence.cassandra

import akka.Done
import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.event.Logging
import akka.persistence.cassandra.session.CassandraSessionSettings
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import com.datastax.driver.core.Session

import scala.concurrent.{ExecutionContext, Future}

/**
  * DISCLAIMER:
  *
  * This object exposes private API from akka-persistence-casssandra.
  *
  * It could be broken at any next version.
  */
object CassandraSessionInitSerialization {

  /**
    * Exposes private CassandraSession#serializedExecution to run all schema mutation calls on single thread one by one
    * to avoid "Column family ID mismatch" exception in Cassandra.
    */
  def serialize(inits: (Session => Future[Done])*)(implicit executionContext: ExecutionContext): Session => Future[Done] = {
    def executeCreate: Session => Future[Done] = { session =>
      def create(): Future[Done] =
        inits.foldLeft(Future.successful(Done: Done)) {
          case (x, init) => x.flatMap(_ => init(session))
        }
      CassandraSession.serializedExecution(
        recur = () => executeCreate(session),
        exec = () => create()
      )
    }
    executeCreate
  }
}


object DefaultJournalCassandraSession {
  /**
    * Creates CassandraSession using settings of default cassandra journal.
    *
    */
  def apply(system: ActorSystem, metricsCategory: String, init: Session => Future[Done])(implicit executionContext: ExecutionContext): CassandraSession = {
    val log = Logging(system, classOf[CassandraSession])
    val provider = SessionProvider(system.asInstanceOf[ExtendedActorSystem], system.settings.config.getConfig("cassandra-journal"))
    val settings = CassandraSessionSettings(system.settings.config.getConfig("cassandra-journal"))
    new CassandraSession(system, provider, settings, executionContext, log, metricsCategory, init)
  }
}