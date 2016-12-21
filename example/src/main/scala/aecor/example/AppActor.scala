package aecor.example

import java.time.Clock

import aecor.aggregate._
import aecor.streaming._
import aecor.example.domain.CardAuthorizationAggregateEvent.CardAuthorizationCreated
import aecor.example.domain._
import aecor.schedule._
import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, ActorSystem, Props }
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.{ Flow, Sink }
import cats.~>
import com.typesafe.config.Config

import scala.concurrent.Future
import scala.concurrent.duration._

object AppActor {
  def props: Props = Props(new AppActor)
}

class AppActor extends Actor with ActorLogging {
  override def receive: Receive = Actor.emptyBehavior

  implicit val system: ActorSystem = context.system
  implicit val materializer: Materializer = ActorMaterializer()

  import materializer.executionContext

  val config: Config = system.settings.config

  val offsetStoreConfig =
    CassandraOffsetStore.Config(config.getString("cassandra-journal.keyspace"))

  val cassandraSession =
    DefaultJournalCassandraSession(
      system,
      "app-session",
      CassandraOffsetStore.createTable(offsetStoreConfig)
    )

  val offsetStore = CassandraOffsetStore(cassandraSession, offsetStoreConfig)

  val journal = CassandraAggregateJournal(system, offsetStore)

  val authorizationRegion: CardAuthorizationAggregateOp ~> Future =
    AggregateSharding(system).start(
      CardAuthorizationAggregate.behavior,
      CardAuthorizationAggregate.entityName,
      CardAuthorizationAggregate.correlation,
      SnapshotPolicy.never
    )

  val accountRegion: AccountAggregateOp ~> Future =
    AggregateSharding(system).start(
      AccountAggregate.behavior(Clock.systemUTC()),
      AccountAggregate.entityName,
      AccountAggregate.correlation,
      SnapshotPolicy.never
    )

  val scheduleEntityName = "Schedule3"

  val schedule: Schedule =
    Schedule(system, scheduleEntityName, 1.day, 10.seconds, offsetStore)

  val cardAuthorizationEventStream =
    new DefaultEventStream(
      system,
      journal
        .committableEventSource[CardAuthorizationAggregateEvent](
          CardAuthorizationAggregate.entityName,
          "CardAuthorization-API"
        )
        .map(_.value)
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

  journal
    .committableEventSource[CardAuthorizationAggregateEvent](
      CardAuthorizationAggregate.entityName,
      "processing"
    )
    .collect {
      case CommittableJournalEntry(offset, _, _, e: CardAuthorizationCreated) =>
        (e, offset)
    }
    .via(authorizationProcessFlow)
    .mapAsync(1)(_.commit())
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
