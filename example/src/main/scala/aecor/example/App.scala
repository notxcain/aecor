package aecor.example

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeoutException

import aecor.core.bus.PublishEntityEvent
import aecor.core.bus.kafka.KafkaEventBus
import aecor.core.entity._
import aecor.core.process.ComposeConfig
import aecor.core.serialization.{DomainEventSerialization, Encoder}
import aecor.example.domain.Account.HoldPlaced
import aecor.example.domain.CardAuthorization.{CardAuthorizationAccepted, CardAuthorizationCreated, CardAuthorizationDeclined, CreateCardAuthorization}
import aecor.example.domain._
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.kafka.{ConsumerSettings, ProducerSettings}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import akka.Done
import cats.data.Xor
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.akkahttpcirce.CirceSupport
import io.circe.Decoder
import kamon.Kamon
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import shapeless.HNil
import io.circe.generic.auto._
import aecor.core.process.CompositeConsumerSettingsSyntax._
import aecor.example.EventStream.ObserverControl
import aecor.example.EventStreamObserverRegistry._
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import aecor.core.process._
import aecor.core.scheduler.ScheduleActor.PublicEvent
import aecor.core.scheduler.{ScheduleActor, ScheduleActorSupervisor}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.event.LoggingAdapter
import akka.http.scaladsl.util.FastFuture

object App extends App {
  Kamon.start()
  val config = ConfigFactory.load()
  val actorSystem = ActorSystem(config.getString("cluster.system-name"))
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

  val kafkaAddress = "localhost"

  def eventBus[Event: Encoder] = KafkaEventBus[Event](
    actorSystem = actorSystem,
    producerSettings = ProducerSettings(actorSystem, new StringSerializer, new DomainEventSerialization).withBootstrapServers(s"$kafkaAddress:9092"),
    bufferSize = 1000,
    offerTimeout = 3.seconds
  )

  val authorizationRegion: EntityRef[CardAuthorization] =
    EntityShardRegion.start[CardAuthorization](actorSystem, eventBus[CardAuthorization.Event], 100, CardAuthorization())

  val accountRegion: EntityRef[Account] =
    EntityShardRegion.start[Account](actorSystem, eventBus[Account.Event], 100, Account())

  val cardAuthorizationProcess = {
    import materializer.executionContext
    val schema =
      from[CardAuthorization, CardAuthorization.Event].collect { case e: CardAuthorizationCreated => e } ::
      from[Account, Account.Event].collect { case e: HoldPlaced => e } ::
      HNil
    Process.start[CardAuthorizationProcess.Input](
      actorSystem = actorSystem,
      kafkaServers = Set(s"$kafkaAddress:9092"),
      name = "CardAuthorizationProcess",
      schema = schema,
      behavior = CardAuthorizationProcess.behavior(accountRegion, authorizationRegion),
      correlation = CardAuthorizationProcess.correlation,
      idleTimeout = 10.seconds,
      numberOfShards = 100
    ).run()
  }

  implicit val loggingEventBusPublish: PublishEntityEvent[LoggingAdapter, ScheduleActor.PublicEvent] =
    new PublishEntityEvent[LoggingAdapter, ScheduleActor.PublicEvent] {
      override def publish(log: LoggingAdapter)(entityName: String, entityId: String, eventEnvelope: EntityEventEnvelope[PublicEvent]): Future[Done] = {
        log.info("Message published [{}] [{}] [{}]", entityName, entityId, eventEnvelope)
        FastFuture.successful(Done)
      }
    }

  val scheduleRegion = ClusterSharding(actorSystem).start(
    typeName = "Schedule2",
    entityProps = ScheduleActorSupervisor.props(log),
    settings = ClusterShardingSettings(actorSystem).withRememberEntities(true),
    extractEntityId = ScheduleActorSupervisor.extractEntityId,
    extractShardId = ScheduleActorSupervisor.extractShardId
  )

  def schedule(dateTime: LocalDateTime): Unit = {
    scheduleRegion ! ScheduleActor.AddScheduleEntry("subscription-payment", dateTime, dateTime.toString)
      log.info("Schedule entry added for [{}]", dateTime)
    }


//  schedule(LocalDateTime.now().plusMinutes(5))
//  schedule(LocalDateTime.now().plusMinutes(3))
//  schedule(LocalDateTime.now().plusMinutes(1))
//  schedule(LocalDateTime.now().plusMinutes(30))
//  schedule(LocalDateTime.now().plusSeconds(20))
//  schedule(LocalDateTime.now().plusSeconds(22))
//  schedule(LocalDateTime.now().plusSeconds(24))
//  schedule(LocalDateTime.now().plusSeconds(26))
//  schedule(LocalDateTime.now().plusSeconds(28))
//  schedule(LocalDateTime.now().plusMinutes(1))
//  schedule(LocalDateTime.now().plusMinutes(2))
//  schedule(LocalDateTime.now().plusMinutes(10))


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

  class AuthorizePaymentAPI(authorization: EntityRef[CardAuthorization], eventStream: EventStream[CardAuthorization.Event]) {
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
              log.debug("Command [{}] processed with result [{}] in [{}]", command, x, (System.nanoTime() - start)/1000000)
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

  class AccountAPI(account: EntityRef[Account]) {
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
    val groupId = "CardAuthorization-API"
    val domainEventSerialization = new DomainEventSerialization
    val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, domainEventSerialization, Set("CardAuthorization"))
      .withBootstrapServers(s"$kafkaAddress:9092")
      .withGroupId(s"$groupId-${UUID.randomUUID()}")

    val config = ComposeConfig(from[CardAuthorization, CardAuthorization.Event])
    val filter = config("CardAuthorization")
    val source = Consumer.plainSource(consumerSettings).map(_.value).map { case (topic, event) => filter(event.payload.toByteArray) }.collect { case Some(e) => e }
    new DefaultEventStream[CardAuthorization.Event](actorSystem, source)
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


object EventStream {
  case class ObserverControl[A](id: ObserverId, result: Future[A])
  type ObserverId = String
}

trait EventStream[Event] {
  def registerObserver[A](f: PartialFunction[Event, A])(implicit timeout: Timeout): Future[ObserverControl[A]]
}

class DefaultEventStream[Event](actorSystem: ActorSystem, source: Source[Event, Control])(implicit materializer: Materializer) extends EventStream[Event] {
  import akka.pattern.ask
  val actor = actorSystem.actorOf(Props(new EventStreamObserverRegistry[Event]), "event-stream-observer-registry")
  source.map(event => ObserveEvent(event)).runWith(Sink.actorRefWithAck(actor, Init, Done, ShutDown))
  override def registerObserver[A](f: PartialFunction[Event, A])(implicit timeout: Timeout): Future[ObserverControl[A]] = {
    import materializer.executionContext
    (actor ? RegisterObserver(f, timeout)).mapTo[ObserverRegistered[A]].map(_.control)
  }
}

object EventStreamObserverRegistry {
  sealed trait Command[+Event]
  case object Init extends Command[Nothing]
  case class RegisterObserver[Event, A](f: PartialFunction[Event, A], timeout: Timeout) extends Command[Event]
  case class DeregisterObserver(id: String) extends Command[Nothing]
  case class ObserveEvent[Event](event: Event) extends Command[Event]
  case object ShutDown extends Command[Nothing]

  case class ObserverRegistered[A](control: ObserverControl[A])
}

class EventStreamObserverRegistry[Event] extends Actor with ActorLogging {
  import EventStreamObserverRegistry._

  def scheduler = context.system.scheduler
  implicit def executionContext = context.dispatcher

  case class Observer(f: PartialFunction[Event, Any], promise: Promise[Any]) {
    def handleEvent(event: Event): Boolean = {
      val handled = f.isDefinedAt(event)
      if (handled) {
        promise.success(f(event))
      }
      handled
    }
  }

  var observers = Map.empty[String, Observer]

  override def receive: Receive = {
    case command: Command[Event] => handleCommand(command)
  }

  def handleCommand(command: Command[Event]): Unit = command match {
    case Init =>
      sender() ! Done
    case RegisterObserver(f, timeout) =>
      val id = UUID.randomUUID().toString
      val promise = Promise[Any]
      observers = observers.updated(id,  Observer(f.asInstanceOf[PartialFunction[Event, Any]], promise))
      scheduler.scheduleOnce(timeout.duration) {
        if (!promise.isCompleted) {
          promise.failure(new TimeoutException())
          self ! DeregisterObserver(id)
        }
      }
      sender() ! ObserverRegistered(ObserverControl(id, promise.future))
    case ObserveEvent(event) =>
      observers = observers.filterNot {
        case (id, observer) => observer.handleEvent(event)
      }
      sender() ! Done
    case DeregisterObserver(id) =>
      observers = observers - id
    case ShutDown =>
      observers.values.foreach(_.promise.failure(new TimeoutException()))
      sender() ! Done
  }
}