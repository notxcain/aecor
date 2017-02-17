
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
libraryDependencies += "io.aecor" %% "aecor-core" % "0.13.2"
scalacOptions += "-Ypartial-unification"
```

### Defining and running behavior

Let's start with entity operations:

```scala
sealed trait SubscriptionOp[A] {
  def subscriptionId: String
}
object SubscriptionOp {
  case class CreateSubscription(subscriptionId: String, userId: String, productId: String, planId: String) extends SubscriptionOp[Unit]
  case class PauseSubscription(subscriptionId: String) extends SubscriptionOp[Unit]
  case class ResumeSubscription(subscriptionId: String) extends SubscriptionOp[Unit]
  case class CancelSubscription(subscriptionId: String) extends SubscriptionOp[Unit]
}
```

Entity events with persistent encoder and decoder:

```scala
import aecor.data.Folded.syntax._
import cats.syntax.option._

sealed trait SubscriptionEvent
object SubscriptionEvent {
  case class SubscriptionCreated(subscriptionId: String, userId: String, productId: String, planId: String) extends SubscriptionEvent
  case class SubscriptionPaused(subscriptionId: String) extends SubscriptionEvent
  case class SubscriptionResumed(subscriptionId: String) extends SubscriptionEvent
  case class SubscriptionCancelled(subscriptionId: String) extends SubscriptionEvent

  implicit val persistentEncoder: PersistentEncoder[SubscriptionEvent] = `define it as you wish`
  implicit val persistentDecoder: PersistentDecoder[SubscriptionEvent] = `and this one too`
}
```

`Folder[F, E, S]` instance represents the ability to fold `E`s into `S`, with effect `F` on each step.
Aecor runtime uses `Folded[A]` data type, with two possible states:
`Next(a)` - says that a should be used as a state for next folding step
`Impossible` - says that folding should be aborted (underlying runtime actor throws `IllegalStateException`)

```scala
sealed trait SubscriptionStatus
object SubscriptionStatus {
  case object Active extends SubscriptionStatus
  case object Paused extends SubscriptionStatus
  case object Cancelled extends SubscriptionStatus
}

case class Subscription(status: SubscriptionStatus)
object Subscription {
  import SubscriptionStatus._

  implicit def folder: Folder[Folded, SubscriptionEvent, Option[Subscription]] =
    Folder.instance(Option.empty[Subscription]) {
      case Some(subscription) => {
        case e: SubscriptionCreated =>
          impossible
        case e: SubscriptionPaused =>
          subscription.copy(status = Paused).some.next
        case e: SubscriptionResumed =>
          subscription.copy(status = Active).some.next
        case e: SubscriptionCancelled =>
          subscription.copy(status = Cancelled).some.next
      }
      case None => {
        case SubscriptionCreated(subscriptionId, userId, productId, planId) =>
          Subscription(Active).some.next
        case _ =>
          impossible
      }
    }
}

```

Now let's define a behavior that converts operation to its handler.
A `Handler[State, Event, Reply]` is just a wrapper around `State => (Seq[Event], Reply)`,
you can think of it as `Kleisli[(Seq[Event], ?), State, Reply]`
i.e. a side-effecting function with a side effect being a sequence of events representing state change caused by operation.

```scala
val behavior = Lambda[SubscriptionOp ~> Handler[Option[Subscription], SubscriptionEvent, ?]] {
  case CreateSubscription(subscriptionId, userId, productId, planId) => {
    case Some(subscription) =>
      // Do nothing reply with ()
      Seq.empty -> ()
    case None =>
      // Produce event and reply with ()
      Seq(SubscriptionCreated(subscriptionId, userId, productId, planId)) -> ()
  }
  case PauseSubscription(subscriptionId) => {
    case Some(subscription) if subscription.status == Active =>
      Seq(SubscriptionPaused(subscriptionId)) -> ()
    case _ =>
      Seq.empty -> ()
  }
  case ResumeSubscription(subscriptionId) => {
    case Some(subscription) if subscription.status == Paused =>
      Seq(SubscriptionResumed(subscriptionId)) -> ()
    case _ =>
      Seq.empty -> ()
  }
  case CancelSubscription(subscriptionId) => {
    case Some(subscription) =>
      Seq(SubscriptionCancelled(subscriptionId)) -> ()
    case _ =>
      Seq.empty -> ()
  }
}
```

Then you define a correlation function, entity name and a value provided by correlation function form unique primary key for aggregate
It should not be changed in the future, at least without prior event migration.

```scala
def correlation: Correlation[SubscriptionOp] = {
  def mk[A](op: SubscriptionOp[A]): CorrelationF[A] = op.subscriptionId
  FunctionK.lift(mk _)
}
```

After that we are ready to launch.

```scala
implicit val system = ActorSystem("foo")

val subscriptions: SubscriptionOp ~> Future =
  AkkaRuntime(system).start(
    entityName = "Subscription",
    behavior,
    correlation,
    Tagging(EventTag("Payment")
  )
```