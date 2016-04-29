package aecor.example

import java.util
import java.util.UUID

import aecor.core.EventBus
import aecor.core.entity._
import aecor.core.message.{Message, MessageId}
import aecor.core.process.{ComposeConfig, CompositeConsumerSettingsSyntax, ProcessActorRegion, ProcessEventAdapter}
import aecor.example.domain.Account.HoldPlaced
import aecor.example.domain.CardAuthorization.{CardAuthorizationAccepted, CardAuthorizationCreated, CardAuthorizationDeclined, CreateCardAuthorization}
import aecor.example.domain._
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, ProducerSettings}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.Timeout
import akka.{Done, NotUsed}
import cats.data.Xor
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.akkahttpcirce.CirceSupport
import io.aecor.message.protobuf.Messages.DomainEvent
import io.circe.Decoder
import io.circe.generic.auto._
import kamon.Kamon
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}
import shapeless.HNil

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class DomainEventSerialization extends Serializer[DomainEvent] with Deserializer[(String, DomainEvent)] {
  override def serialize(topic: String, data: DomainEvent): Array[Byte] =
    data.toByteArray

  override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {}

  override def close(): Unit = {}

  override def deserialize(topic: String, data: Array[Byte]): (String, DomainEvent) =
    (topic, DomainEvent.parseFrom(data))
}

object App extends App {
  Kamon.start()
  val actorSystem = ActorSystem("playground")
  actorSystem.actorOf(RootActor.props, "root")
  actorSystem.registerOnTermination {
    System.exit(1)
  }
}

object RootActor {
  def props: Props = Props(new RootActor)
}

class RootActor extends Actor with ActorLogging with CirceSupport {
  override def receive: Receive = Actor.emptyBehavior
  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()

  val domainEventSerialization = new DomainEventSerialization

  val kafkaAddress = "localhost"

  val publisherActor = {
    val settings = ProducerSettings(actorSystem, new StringSerializer, domainEventSerialization)
      .withBootstrapServers(s"$kafkaAddress:9092")
    val props = Props(new EventBus(settings, 1000))
    actorSystem.actorOf(props, "kafka-publisher")
  }

  val authorizationRegion: EntityRef[CardAuthorization] =
    EntityActorRegion.start[CardAuthorization](actorSystem, CardAuthorization.State.initial, publisherActor, 100)

  val accountRegion: EntityRef[Account] =
    EntityActorRegion.start[Account](actorSystem, Account.State.initial, publisherActor, 100)

  def startCardAuthorizationProcess(name: String) = {
    val counterProcessRegion = ProcessActorRegion.start(
      actorSystem,
      name,
      CardAuthorizationProcess.correlation,
      CardAuthorizationProcess.behavior(accountRegion, authorizationRegion),
      idleTimeout = 10.seconds,
      numberOfShards = 100
    )

    val processStreamConfig = ComposeConfig {
      import CompositeConsumerSettingsSyntax._
      from[CardAuthorization, CardAuthorization.Event].collect { case e: CardAuthorizationCreated => e } ::
      from[Account, Account.Event].collect { case e: HoldPlaced => e } ::
      HNil
    }

    val adapterProps = ProcessEventAdapter.props[CardAuthorizationProcess.Input](counterProcessRegion) { (topic, event) =>
      processStreamConfig.get(topic).flatMap { f =>
        f(event.payload.toByteArray)
      }
    }

    val counterProcessEventAdapter = context.actorOf(adapterProps)
    val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, domainEventSerialization, processStreamConfig.keySet)
      .withBootstrapServers(s"$kafkaAddress:9092")
      .withGroupId(name)
      .withClientId(s"$name-${UUID.randomUUID()}")
      .withProperty("auto.offset.reset", "earliest")


    val sink = Sink.actorRefWithAck(
      ref = counterProcessEventAdapter,
      onInitMessage = ProcessEventAdapter.Init,
      ackMessage = ProcessEventAdapter.Forwarded,
      onCompleteMessage = Done
    )

    Consumer.committableSource(consumerSettings)
      .map(ProcessEventAdapter.Forward)
      .toMat(sink)(Keep.left)
  }

  startCardAuthorizationProcess("CAP-4").run()

  val queries = PersistenceQuery(actorSystem).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  implicit val askTimeout: Timeout = Timeout(5.seconds)

  object AuthorizePaymentAPI {
    sealed trait Result extends Product with Serializable
    case object Authorized extends Result
    case class Declined(reason: String) extends Result
    sealed trait DTO
    object DTO {
      case class AuthorizePayment(cardAuthorizationId: String, accountId: String, amount: Long, acquireId: Long, terminalId: Long) extends DTO
      implicit val decoder: Decoder[DTO] = shapeless.cachedImplicit
    }
  }

  class AuthorizePaymentAPI(authorizationRegion: EntityRef[CardAuthorization]) {
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
        (authorizationRegion ? Message(MessageId.generate, command, NotUsed))
            .map(_.result)
            .flatMap {
              case EntityActor.Rejected(rejection) =>
                log.debug("Command [{}] rejected [{}]", command.cardAuthorizationId, rejection)
                Future.successful(Xor.left(rejection))
              case EntityActor.Accepted =>
                log.debug("Command [{}] accepted", command.cardAuthorizationId)
                val persistenceId = EntityName[CardAuthorization].value + "-" + cardAuthorizationId
                queries.eventsByPersistenceId(persistenceId, 0, Long.MaxValue)
                  .map(_.event)
                    .collect {
                      case EntityEventEnvelope(_, event: CardAuthorization.Event, _, _) => event
                    }
                  .collect {
                    case e: CardAuthorizationDeclined => Declined(e.reason.toString)
                    case e: CardAuthorizationAccepted => Authorized
                  }
                  .take(1)
                  .runWith(Sink.head)
                  .map(Xor.right).map { x =>
                    log.debug("Command processed [{}] in [{}]", command, (System.nanoTime() - start)/1000000)
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

  class AccountAPI(accountShardRegion: EntityRef[Account]) {
    import AccountAPI._
    def openAccount(dto: DTO.OpenAccount)(implicit ec: ExecutionContext): Future[String Xor Done] = dto match {
      case DTO.OpenAccount(accountId) =>
        (accountShardRegion ? Message(MessageId(s"OpenAccount-$accountId"), Account.OpenAccount(AccountId(accountId)), NotUsed))
          .map(_.result)
          .flatMap {
            case EntityActor.Accepted =>
              log.debug("Command [{}] accepted", dto)
              Future.successful(Xor.Right(Done: Done))
            case EntityActor.Rejected(rejection) =>
              Future.successful(Xor.Left(rejection.toString))
          }
    }
    def creditAccount(dto: DTO.CreditAccount)(implicit ec: ExecutionContext): Future[String Xor Done] = dto match {
      case DTO.CreditAccount(accountId, transactionId, amount) =>

      (accountShardRegion ? Message(MessageId(s"CreditAccount-$transactionId"), Account.CreditAccount(AccountId(accountId), TransactionId(transactionId), Amount(amount)), NotUsed))
        .map(_.result)
        .flatMap {
          case EntityActor.Accepted =>
            log.debug("Command [{}] accepted", dto)
            Future.successful(Xor.Right(Done: Done))
          case EntityActor.Rejected(rejection) =>
            Future.successful(Xor.Left(rejection.toString))
        }
    }

  }

  val authorizePaymentAPI = new AuthorizePaymentAPI(authorizationRegion)
  val accountApi = new AccountAPI(accountRegion)
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
          case dto : DTO.AuthorizePayment =>
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
            case dto : DTO.CreditAccount=>
              complete {
                accountApi.creditAccount(dto).map {
                  case Xor.Left(e) => StatusCodes.BadRequest -> e.toString
                  case Xor.Right(result) => StatusCodes.OK -> ""
                }
              }
            case dto : DTO.OpenAccount =>
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

