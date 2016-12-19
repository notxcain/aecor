package aecor.example

import java.time.Clock

import aecor.core.aggregate._
import aecor.core.streaming._
import aecor.example.domain.CardAuthorizationAggregateEvent.CardAuthorizationCreated
import aecor.example.domain._
import aecor.schedule._
import akka.actor.{Actor, ActorLogging, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import cats.~>

import scala.concurrent.Future
import scala.concurrent.duration._

object AppActor {
  def props: Props = Props(new AppActor)
}

class AppActor extends Actor with ActorLogging {
  override def receive: Receive = Actor.emptyBehavior

  implicit val system = context.system
  implicit val materializer = ActorMaterializer()

  import materializer.executionContext

  val config = system.settings.config

  val queries = new CassandraOffsetStore.Queries(system)

  val sessionWrapper =
    DefaultJournalCassandraSession(system, "app-session", queries.init)

  val offsetStore = new CassandraOffsetStore(sessionWrapper, queries)

  val journal = AggregateJournal(system, offsetStore)

  val authorizationRegion: CardAuthorizationAggregateOp ~> Future =
    AggregateSharding(system).start(CardAuthorizationAggregate.behavior,
                                    CardAuthorizationAggregate.entityName,
                                    CardAuthorizationAggregate.correlation)

  val accountRegion: AccountAggregateOp ~> Future =
    AggregateSharding(system).start(
      AccountAggregate.behavior(Clock.systemUTC()),
      AccountAggregate.entityName,
      AccountAggregate.correlation)

  val scheduleEntityName = "Schedule3"

  val schedule: Schedule =
    Schedule(system, scheduleEntityName, 1.day, 10.seconds, offsetStore)

  val cardAuthorizationEventStream =
    new DefaultEventStream(
      system,
      journal
        .committableEventSource[CardAuthorizationAggregateEvent](
          CardAuthorizationAggregate.entityName,
          "CardAuthorization-API")
        .map(_.value))

  val authorizePaymentAPI = new AuthorizePaymentAPI(
    authorizationRegion,
    cardAuthorizationEventStream,
    Logging(system, classOf[AuthorizePaymentAPI]))
  val accountApi = new AccountAPI(accountRegion)

  import freek._

  val interpreter = accountRegion :&: authorizationRegion

  journal
    .committableEventSource[CardAuthorizationAggregateEvent](
      CardAuthorizationAggregate.entityName,
      "processing")
    .collect {
      case CommittableJournalEntry(offset,
                                   _,
                                   _,
                                   e: CardAuthorizationCreated) =>
        (e, offset)
    }
    .via(AuthorizationProcess.flow(8, interpreter))
    .mapAsync(1)(_.commitScaladsl())
    .runWith(Sink.ignore)

  val route = path("check") {
    get {
      complete(StatusCodes.OK)
    }
  } ~
      AuthorizePaymentAPI.route(authorizePaymentAPI) ~
      AccountAPI.route(accountApi)

  Http()
    .bindAndHandle(route,
                   config.getString("http.interface"),
                   config.getInt("http.port"))
    .onComplete { result =>
      log.info("Bind result [{}]", result)
    }

}
