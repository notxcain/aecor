package aecor.example

import java.time.Clock

import aecor.data.{ Committable, Tagging }
import aecor.example.domain.CardAuthorizationAggregateEvent.CardAuthorizationCreated
import aecor.example.domain._
import aecor.runtime.akkapersistence.{
  AkkaPersistenceRuntime,
  CassandraAggregateJournal,
  CassandraOffsetStore,
  JournalEntry
}
import aecor.streaming.ConsumerId
import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{ complete, get, path }
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.{ Flow, Sink }
import cats.~>
import com.typesafe.config.{ Config, ConfigFactory }
import monix.eval.Task
import java.time.Clock

import aecor.data.{ Committable, Tagging }
import aecor.example.domain.CardAuthorizationAggregateEvent.CardAuthorizationCreated
import aecor.example.domain._
import aecor.streaming._
import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, ActorSystem, Props }
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.scaladsl.{ Flow, Sink }
import akka.stream.{ ActorMaterializer, Materializer }
import cats.~>
import com.typesafe.config.Config
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import aecor.effect.Async.ops._
import monix.cats._
import aecor.effect.monix._
import aecor.runtime.akkapersistence.{
  AkkaPersistenceRuntime,
  CassandraAggregateJournal,
  CassandraOffsetStore,
  JournalEntry
}

object App {
  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    implicit val system: ActorSystem = ActorSystem(config.getString("cluster.system-name"))
    system.registerOnTermination {
      System.exit(1)
    }
    implicit val materializer: Materializer = ActorMaterializer()

    val offsetStoreConfig =
      CassandraOffsetStore.Config(config.getString("cassandra-journal.keyspace"))

    val cassandraSession =
      DefaultJournalCassandraSession(
        system,
        "app-session",
        CassandraOffsetStore.createTable(offsetStoreConfig)
      )

    val startAuthorizationRegion: Task[TransactionOp ~> Task] =
      AkkaPersistenceRuntime[Task](system).start(
        CardAuthorizationAggregate.entityName,
        CardAuthorizationAggregate.commandHandler,
        CardAuthorizationAggregate.correlation,
        Tagging(CardAuthorizationAggregate.entityNameTag)
      )

    val startAccountRegion: Task[AccountAggregateOp ~> Task] =
      AkkaPersistenceRuntime[Task](system).start(
        AccountAggregate.entityName,
        AccountAggregate.commandHandler(Clock.systemUTC()),
        AccountAggregate.correlation,
        Tagging(AccountAggregate.entityNameTag)
      )

    def run(accountRegion: AccountAggregateOp ~> Task,
            authorizationRegion: TransactionOp ~> Task): Task[Unit] =
      Task {
        val scheduleEntityName = "Schedule3"
        val authEventJournal =
          CassandraAggregateJournal[CardAuthorizationAggregateEvent](system)
        val cardAuthorizationEventStream =
          new DefaultEventStream(
            system,
            authEventJournal
              .eventsByTag(CardAuthorizationAggregate.entityNameTag, Option.empty)
              .map(_.event)
          )

        val authorizePaymentAPI = new AuthorizePaymentAPI(
          authorizationRegion,
          cardAuthorizationEventStream,
          Logging(system, classOf[AuthorizePaymentAPI])
        )
        val accountApi = new AccountAPI(accountRegion)

        import freek._

        def authorizationProcessFlow[PassThrough]
          : Flow[(CardAuthorizationCreated, PassThrough), PassThrough, NotUsed] =
          AuthorizationProcess.flow(8, accountRegion :&: authorizationRegion)

        authEventJournal
          .committableEventsByTag(
            CassandraOffsetStore[Task](cassandraSession, offsetStoreConfig),
            CardAuthorizationAggregate.entityNameTag,
            ConsumerId("processing")
          )
          .collect {
            case x @ Committable(_, JournalEntry(offset, _, _, e: CardAuthorizationCreated)) =>
              (e, x)
          }
          .via(authorizationProcessFlow)
          .mapAsync(1)(_.commit().unsafeRun)
          .runWith(Sink.ignore)

        val route = path("check") {
          get {
            complete(StatusCodes.OK)
          }
        } ~
          AuthorizePaymentAPI.route(authorizePaymentAPI) ~
          AccountAPI.route(accountApi)

        Http()
          .bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))
          .onComplete { result =>
            log.info("Bind result [{}]", result)
          }
      }

    val app = for {
      authorizationRegion <- startAuthorizationRegion
      accountRegion <- startAccountRegion
      _ <- run(accountRegion, authorizationRegion)
    } yield ()

    app.runAsync
  }

}
