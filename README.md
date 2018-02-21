
[![Build Status](https://img.shields.io/travis/notxcain/aecor/master.svg)](https://travis-ci.org/notxcain/aecor)
[![Maven Central](https://img.shields.io/maven-central/v/io.aecor/aecor-core_2.11.svg)](https://github.com/notxcain/aecor)
[![Join the chat at https://gitter.im/notxcain/aecor](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/notxcain/aecor)


# Aecor
### Typeful runtime for eventsourced behaviors

Aecor is an opinionated library to help building scalable, distributed eventsourced services written in Scala. It uses [Akka](https://github.com/akka/akka) for distribution and fault tolerance.
With the help of [Cats](https://github.com/typelevel/cats/) and [Shapeless](https://github.com/milessabin/shapeless) to reach type safety.

Aecor works on Scala 2.11 and 2.12 with Java 8.

The name `Aecor` (_lat. ocean_) is inspired by a vision of modern distributed applications, as an ocean of messages with pure behaviors floating in it.
    
### Installing Aecor

To start using Aecor Runtime add the following to your `build.sbt` file:

```scala
scalaOrganization := "org.typelevel"
libraryDependencies += "io.aecor" %% "aecor-core" % "0.15.0"
scalacOptions += "-Ypartial-unification"
```

### Defining and running behavior

Let's start with defining domain events:

```scala
sealed abstract class SubscriptionEvent
object SubscriptionEvent {
  final case class SubscriptionCreated(userId: String, productId: String, planId: String) extends SubscriptionEvent
  final case object SubscriptionPaused extends SubscriptionEvent
  final case object SubscriptionResumed extends SubscriptionEvent
  final case object SubscriptionCancelled extends SubscriptionEvent

  implicit val persistentEncoder: PersistentEncoder[SubscriptionEvent] = ???
  implicit val persistentDecoder: PersistentDecoder[SubscriptionEvent] = ???
}

sealed trait SubscriptionStatus
object SubscriptionStatus {
  case object Active extends SubscriptionStatus
  case object Paused extends SubscriptionStatus
  case object Cancelled extends SubscriptionStatus
}

final case class SubscriptionState(status: SubscriptionStatus) {
  def applyEvent(e: SubscriptionEvent): Folded[Subscription] = e match {
    case e: SubscriptionCreated =>
      impossible
    case e: SubscriptionPaused =>
      subscription.copy(status = Paused).next
    case e: SubscriptionResumed =>
      subscription.copy(status = Active).next
    case e: SubscriptionCancelled =>
      subscription.copy(status = Cancelled).next
  }
}
object SubscriptionState {
  import SubscriptionStatus._
  def init(e: SubscriptionEvent): Folded[SubscriptionState] = e match {
    case SubscriptionCreated(subscriptionId, userId, productId, planId) =>
      Subscription(Active).next
    case _ => impossible
  }
}

@wireProtocol
trait Subscription[F[_]] {
  def createSubscription(userId: String, productId: String, planId: String): F[Unit]
  def pauseSubscription: F[Unit]
  def resumeSubscription: F[Unit]
  def cancelSubscription: F[Unit]
}

object SubscriptionActions extends Subscription[Action[Option[Subscription], SubscriptionEvent, ?]] {
  def createSubscription(userId: String, productId: String, planId: String): F[Unit] = Action {
    case Some(subscription) =>
    // Do nothing reply with ()
    List.empty -> ()
  case None =>
    // Produce event and reply with ()
    List(SubscriptionCreated(userId, productId, planId)) -> ()
  }
  def pauseSubscription: F[Unit] = Action {
    case Some(subscription) if subscription.status == Active =>
      List(SubscriptionPaused) -> ()
    case _ =>
      List.empty -> ()
  }
  def resumeSubscription: F[Unit] = Action {
    case Some(subscription) if subscription.status == Paused =>
      List(SubscriptionResumed) -> ()
    case _ =>
      List.empty -> ()
  }
   
  def cancelSubscription: F[Unit] = Action {
    case Some(subscription) =>
      List(SubscriptionCancelled) -> ()
    case _ =>
      List.empty -> ()
  }
}

import monix.eval.Task
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime

val system = ActorSystem("system")

val runtime = AkkaPersistenctRuntime(system)

val behavior = EventsourcedBehavior.optional(
  SubscriptionActions,
  SubscriptionState.init,
  _.applyEvent(_)
)

val deploySubscriptions: Task[SubscriptionId => Subscription[Task]] =
  runtime.deploy(
    "Subscription",
    behavior.lifted[Task],
    Tagging.const[SubscriptionId](EventTag("Subscription"))
  )
```
