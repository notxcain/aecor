package aecor.example

import java.util.UUID

import aecor.core.aggregate._
import aecor.core.process.CompositeConsumerSettingsSyntax._
import aecor.core.process.{ComposeConfig, HandleEvent, ProcessSharding}
import aecor.core.serialization.CirceSupport._
import aecor.core.serialization.protobuf.EventEnvelope
import aecor.core.serialization.{Encoder, EntityEventEnvelopeSerde}
import aecor.core.streaming._
import aecor.example.domain.Account.TransactionAuthorized
import aecor.example.domain.CardAuthorization.{CardAuthorizationAccepted, CardAuthorizationCreated, CardAuthorizationDeclined, CreateCardAuthorization}
import aecor.example.domain._
import akka.Done
import akka.actor.{Actor, ActorLogging, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import cats.data.Xor
import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.akkahttpcirce.CirceSupport._
import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import shapeless.{Coproduct, HNil}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object AppActor {
  def props: Props = Props(new AppActor)
}

class AppActor extends Actor with ActorLogging {
  override def receive: Receive = Actor.emptyBehavior

  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()

  val kafkaAddress = "localhost"

  val producerSettings = ProducerSettings(actorSystem, new StringSerializer, new EntityEventEnvelopeSerde).withBootstrapServers(s"$kafkaAddress:9092")

  val authorizationRegion: AggregateRef[CardAuthorization] =
    AggregateSharding(actorSystem).start(CardAuthorization())()

  val accountRegion: AggregateRef[Account] =
    AggregateSharding(actorSystem).start(Account())()

  val extendedCassandraReadJournal = ExtendedCassandraReadJournal(actorSystem, PersistenceQuery(actorSystem).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier))

  def eventSink[A: Encoder](topicName: String) = Flow[CommittableJournalEntry[AggregateEventEnvelope[A]]].map {
    case CommittableJournalEntry(offset, persistenceId, sequenceNr, AggregateEventEnvelope(eventId, event, timestamp, causedBy)) =>
      val payload = EventEnvelope(eventId.value, persistenceId, sequenceNr, ByteString.copyFrom(Encoder[A].encode(event)), timestamp.toEpochMilli)
      val producerRecord = new ProducerRecord(topicName, null, payload.timestamp, persistenceId, payload)
      ProducerMessage.Message(producerRecord, offset)
  }.to(Producer.commitableSink(producerSettings))

  val journal = Journal(extendedCassandraReadJournal)

  {
    import materializer.executionContext
    val replicator = Journal(extendedCassandraReadJournal)
    replicator.committableEventSourceFor[Account](consumerId = "kafka_replicator").to(eventSink("Account")).run()
    replicator.committableEventSourceFor[CardAuthorization](consumerId = "kafka_replicator").to(eventSink("CardAuthorization")).run()
  }

  val cardAuthorizationProcess = {
    import materializer.executionContext

    val schema =
      from[CardAuthorization]().collect { case e: CardAuthorizationCreated => e } ::
        from[Account]().collect { case e: TransactionAuthorized => e } ::
        HNil
    val process = ProcessSharding(actorSystem)

    val accountEvents =
      journal.committableEventSourceFor[Account](consumerId = "CardAuthorizationProcess").collect {
        case CommittableJournalEntry(offset, persistenceId, sequenceNr, AggregateEventEnvelope(id, event: Account.TransactionAuthorized, ts, causedBy)) =>
          CommittableMessage(offset, HandleEvent(id, Coproduct[AuthorizationProcess.Input](event)))
      }
    val caEvents =
      journal.committableEventSourceFor[CardAuthorization](consumerId = "CardAuthorizationProcess").collect {
        case CommittableJournalEntry(offset, persistenceId, sequenceNr, AggregateEventEnvelope(id, event: CardAuthorizationCreated, ts, causedBy)) =>
          CommittableMessage(offset, HandleEvent(id, Coproduct[AuthorizationProcess.Input](event)))
      }

    val directSource = accountEvents.merge(caEvents)

    val sink = process.sink(
                             name = "CardAuthorizationProcess",
                             behavior = AuthorizationProcess.behavior(accountRegion, authorizationRegion),
                             correlation = AuthorizationProcess.correlation,
                             idleTimeout = 10.seconds
                           )
    directSource.to(sink).run()
  }

  object AuthorizePaymentAPI {

    sealed trait Result

    case object Authorized extends Result

    case class Declined(reason: String) extends Result

    sealed trait DTO

    object DTO {

      case class AuthorizePayment(cardAuthorizationId: String, accountId: String, amount: Long, acquireId: Long, terminalId: Long) extends DTO

      implicit val decoder: Decoder[DTO] = shapeless.cachedImplicit
    }

  }

  class AuthorizePaymentAPI(authorization: AggregateRef[CardAuthorization], eventStream: EventStream[CardAuthorization.Event]) {

    import AuthorizePaymentAPI._
    import DTO._

    def authorizePayment(dto: AuthorizePayment)(implicit ec: ExecutionContext): Future[Xor[CardAuthorization.CreateCardAuthorizationRejection, Result]] = dto match {
      case AuthorizePayment(cardAuthorizationId, accountId, amount, acquireId, terminalId) =>
        val command = CreateCardAuthorization(
                                               CardAuthorizationId(cardAuthorizationId),
                                               AccountId(accountId),
                                               Amount(amount),
                                               AcquireId(acquireId),
                                               TerminalId(terminalId)
                                             )
        log.debug("Sending command [{}]", command)
        val start = System.nanoTime()
        val id = UUID.randomUUID.toString
        eventStream.registerObserver {
          case e: CardAuthorizationDeclined if e.cardAuthorizationId.value == cardAuthorizationId => Declined(e.reason.toString)
          case e: CardAuthorizationAccepted if e.cardAuthorizationId.value == cardAuthorizationId => Authorized
        }.flatMap { observer =>
          authorization
          .handle(id, command)
          .flatMap {
            case Rejected(rejection) => Future.successful(Xor.left(rejection))
            case Accepted => observer.result.map(Xor.right)
          }.map { x =>
            log.debug("Command [{}] processed with result [{}] in [{}]", command, x, (System.nanoTime() - start) / 1000000)
            x
          }
        }

    }
  }

  object AccountAPI {

    sealed trait DTO

    object DTO {

      case class CreditAccount(accountId: String, transactionId: String, amount: Long) extends DTO

      case class OpenAccount(accountId: String) extends DTO

      implicit val decoder: Decoder[DTO] = shapeless.cachedImplicit
    }

  }

  class AccountAPI(account: AggregateRef[Account]) {

    import AccountAPI._

    def openAccount(dto: DTO.OpenAccount)(implicit ec: ExecutionContext): Future[String Xor Done] = dto match {
      case DTO.OpenAccount(accountId) =>
        account
        .handle(UUID.randomUUID.toString, Account.OpenAccount(AccountId(accountId)))
        .flatMap {
          case Accepted =>
            log.debug("Command [{}] accepted", dto)
            Future.successful(Xor.Right(Done: Done))
          case Rejected(rejection) =>
            Future.successful(Xor.Left(rejection.toString))
        }
    }

    def creditAccount(dto: DTO.CreditAccount)(implicit ec: ExecutionContext): Future[String Xor Done] = dto match {
      case DTO.CreditAccount(accountId, transactionId, amount) =>
        account
        .handle(UUID.randomUUID().toString, Account.CreditAccount(AccountId(accountId), TransactionId(transactionId), Amount(amount)))
        .flatMap {
          case Accepted =>
            log.debug("Command [{}] accepted", dto)
            Future.successful(Xor.Right(Done: Done))
          case Rejected(rejection) =>
            Future.successful(Xor.Left(rejection.toString))
        }
    }
  }

  val cardAuthorizationEventStreamSource = {
    import actorSystem.dispatcher
    val groupId = "CardAuthorization-API"
    val domainEventSerialization = new EntityEventEnvelopeSerde
    val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, domainEventSerialization)
                           .withBootstrapServers(s"$kafkaAddress:9092")
                           .withGroupId(s"$groupId-${UUID.randomUUID()}")

    val config = ComposeConfig(from[CardAuthorization]())
    val filter = config("CardAuthorization")
    val source = Consumer.plainSource(consumerSettings, Subscriptions.topics("CardAuthorization"))
                 .map(_.value)
                 .map {
                   case (topic, event) =>
                     filter(event.event.toByteArray)
                 }
                 .collect { case Some(e) => e }
    val so = journal.committableEventSourceFor[CardAuthorization]("groupId").map(_.value.event)
    new DefaultEventStream[CardAuthorization.Event](actorSystem, so)
  }


  val authorizePaymentAPI = new AuthorizePaymentAPI(authorizationRegion, cardAuthorizationEventStreamSource)
  val accountApi = new AccountAPI(accountRegion)

  implicit val askTimeout: Timeout = Timeout(5.seconds)


  val route = path("check") {
    get {
      complete(StatusCodes.OK)
    }
  } ~
    path("authorization") {
      import AuthorizePaymentAPI._
      extractExecutionContext { implicit ec =>
        post {
          entity(as[DTO]) {
            case dto: DTO.AuthorizePayment =>
              complete {
                authorizePaymentAPI.authorizePayment(dto).map {
                  case Xor.Left(e) => StatusCodes.BadRequest -> e.toString
                  case Xor.Right(result) => result match {
                    case AuthorizePaymentAPI.Authorized => StatusCodes.OK -> "Authorized"
                    case AuthorizePaymentAPI.Declined(reason) => StatusCodes.BadRequest -> s"Declined: $reason"
                  }
                }
              }
          }
        }
      }
    } ~
    path("accounts") {
      import AccountAPI._
      extractExecutionContext { implicit ec =>
        post {
          entity(as[DTO]) {
            case dto: DTO.CreditAccount =>
              complete {
                accountApi.creditAccount(dto).map {
                  case Xor.Left(e) => StatusCodes.BadRequest -> e.toString
                  case Xor.Right(result) => StatusCodes.OK -> ""
                }
              }
            case dto: DTO.OpenAccount =>
              complete {
                accountApi.openAccount(dto).map {
                  case Xor.Left(e) => StatusCodes.BadRequest -> e.toString
                  case Xor.Right(result) => StatusCodes.OK -> ""
                }
              }
          }
        }
      }
    }
  val config = ConfigFactory.load()
  Http().bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))
  .onComplete { result => log.info("Bind result [{}]", result) }(materializer.executionContext)

}
