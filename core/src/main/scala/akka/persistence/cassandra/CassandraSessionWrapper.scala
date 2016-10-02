package akka.persistence.cassandra

import akka.Done
import akka.actor.ActorSystem
import akka.event.Logging
import com.datastax.driver.core.{PreparedStatement, ResultSet, Session, Statement}

import scala.concurrent.{ExecutionContext, Future}

class CassandraSessionWrapper(system: ActorSystem, init: Session => Future[_])(implicit executionContext: ExecutionContext) {

  val log = Logging(system, classOf[CassandraSessionWrapper])
  val metricsCategory = "CassandraSessionWrapper"
  val settings = new CassandraPluginConfig(system, system.settings.config.getConfig("cassandra-journal"))
  val session = new CassandraSession(system, settings, executionContext, log, metricsCategory, executeCreate)

  def executeCreate(session: Session): Future[Done] = {
    def create(): Future[Done] = init(session).map(_ => Done)
    CassandraSession.serializedExecution(
      recur = () => executeCreate(session),
      exec = () => create()
    )
  }

  def select(stmt: Statement): Future[ResultSet] =
    session.select(stmt)

  def prepare(stmt: String): Future[PreparedStatement] =
    session.prepare(stmt)

  def executeWrite(stmt: Statement): Future[Unit] =
    session.executeWrite(stmt)
}


object CassandraSessionWrapper {
  def create(system: ActorSystem, inits: (Session => Future[_])*)(implicit executionContext: ExecutionContext): CassandraSessionWrapper = {
    def sequence: Session => Future[_] = { session =>
      inits.foldLeft[Future[_]](Future.successful(())) {
        case (x, init) => x.flatMap(_ => init(session))
      }
    }
    new CassandraSessionWrapper(system, sequence)
  }

}