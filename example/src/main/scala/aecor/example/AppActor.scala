package aecor.example

import aecor.api.Router.ops._
import aecor.core.aggregate._
import aecor.core.streaming._
import aecor.example.domain._
import aecor.schedule._
import akka.actor.{Actor, ActorLogging, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.persistence.cassandra.CassandraSessionWrapper
import akka.stream.ActorMaterializer

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

  val sessionWrapper = CassandraSessionWrapper.create(system, queries.init)

  val offsetStore = new CassandraOffsetStore(sessionWrapper, queries)

  val journal = AggregateJournal(system, offsetStore)

  val authorizationRegion: AggregateRegionRef[CardAuthorization.Command] =
    AggregateSharding(system).start(CardAuthorization())

  val accountRegion: AggregateRegionRef[Account.Command] =
    AggregateSharding(system).start(Account())


  val scheduleEntityName = "Schedule3"

  val schedule: Schedule = Schedule(system, scheduleEntityName, 1.day, 10.seconds, offsetStore)



  val cardAuthorizationEventStream =
    new DefaultEventStream(system, journal.committableEventSourceFor[CardAuthorization]("CardAuthorization-API").map(_.value))


  val authorizePaymentAPI = new AuthorizePaymentAPI(authorizationRegion, cardAuthorizationEventStream, Logging(system, classOf[AuthorizePaymentAPI]))
  val accountApi = new AccountAPI(accountRegion)

  val route = path("check") {
    get {
      complete(StatusCodes.OK)
    }
  } ~
    authorizePaymentAPI.route ~
    accountApi.route

  Http().bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))
  .onComplete { result => log.info("Bind result [{}]", result) }

}
