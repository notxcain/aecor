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
   * Creates CassandraSession using settings of default cassandra journal.
   * Inits are sequenced and are limited to one thread at a time to
   * reduce the risk of (annoying) "Column family ID mismatch" exception
   * during concurrent schema mutation by other sessions.
   */
  def createSession(system: ActorSystem, metricsCategory: String, inits: (Session => Future[Done])*)(implicit executionContext: ExecutionContext): CassandraSession = {
    val log = Logging(system, classOf[CassandraSession])
    val provider = SessionProvider(system.asInstanceOf[ExtendedActorSystem], system.settings.config.getConfig("cassandra-journal"))
    val settings = CassandraSessionSettings(system.settings.config.getConfig("cassandra-journal"))
    new CassandraSession(system, provider, settings, executionContext, log, metricsCategory, serialize(inits: _*))
  }

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