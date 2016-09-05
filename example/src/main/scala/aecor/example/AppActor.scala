package aecor.example

import aecor.api.Router.ops._
import aecor.core.aggregate._
import aecor.core.process.{HandleEvent, ProcessSharding}
import aecor.schedule._
import aecor.core.serialization.CirceSupport._
import aecor.core.serialization.kafka.EventEnvelopeSerializer
import aecor.core.streaming._
import aecor.example.domain.CardAuthorization.CardAuthorizationCreated
import aecor.example.domain._
import akka.actor.{Actor, ActorLogging, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.kafka.ProducerSettings
import akka.stream.ActorMaterializer
import org.apache.kafka.common.serialization.StringSerializer

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

  val journal = AggregateJournal(system)

  val kafkaAddress = "localhost"

  val producerSettings = ProducerSettings(system, new StringSerializer, new EventEnvelopeSerializer).withBootstrapServers(s"$kafkaAddress:9092")

  val authorizationRegion: AggregateRegionRef[CardAuthorization.Command] =
    AggregateSharding(system).start(CardAuthorization())

  val accountRegion: AggregateRegionRef[Account.Command] =
    AggregateSharding(system).start(Account())


  val scheduleEntityName = "Schedule3"

  val schedule: Schedule = Schedule(system, scheduleEntityName, 1.day, 10.seconds)

  journal.committableEventSourceFor[Account](consumerId = "kafka_replicator").to(Kafka.eventSink(producerSettings, "Account")).run()
  journal.committableEventSourceFor[CardAuthorization](consumerId = "kafka_replicator").to(Kafka.eventSink(producerSettings, "CardAuthorization")).run()


  val cardAuthorizationProcess = {
    val process = ProcessSharding(system)

    val source =
      journal.committableEventSourceFor[CardAuthorization](consumerId = "CardAuthorizationProcess").collect {
        case CommittableJournalEntry(offset, persistenceId, sequenceNr, AggregateEvent(id, event: CardAuthorizationCreated, ts)) =>
          ProcessSharding.Message(HandleEvent(id, event), offset)
      }


    val sink = process.committableSink(
                             name = AuthorizationProcess.name,
                             behavior = new AuthorizationProcess(accountRegion, authorizationRegion),
                             correlation = AuthorizationProcess.correlation
                           )
    source.to(sink)
  }

  cardAuthorizationProcess.run()

  val cardAuthorizationEventStream =
    new DefaultEventStream(system, journal.committableEventSourceFor[CardAuthorization]("CardAuthorization-API").map(_.value.event))


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
