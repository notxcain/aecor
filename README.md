
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

Then we define how this events affect aggregate state:

`Folder[F, E, S]` instance represents the ability to fold `E`s into `S`, with effect `F` on each step.
Aecor runtime uses `Folded[A]` data type, with two possible states:
`Next(a)` - says that `a` should be used as a state for next folding step
`Impossible` - says that folding should be aborted (e.g. underlying runtime actor throws `IllegalStateException`)

```scala
sealed trait SubscriptionStatus
object SubscriptionStatus {
  case object Active extends SubscriptionStatus
  case object Paused extends SubscriptionStatus
  case object Cancelled extends SubscriptionStatus
}

case class Subscription(status: SubscriptionStatus) {
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
object Subscription {
  import SubscriptionStatus._
  def init(e: SubscriptionEvent): Folded[Subscription] = e match {
    case SubscriptionCreated(subscriptionId, userId, productId, planId) =>
      Subscription(Active).next
    case _ => impossible
  }
  def folder: Folder[Folded, SubscriptionEvent, Option[Subscription]] =
    Folder.optionInstance(init)(_.applyEvent)
}
```


Now let's define a behavior that converts operation to its handler.
A `Handler[F, State, Event, Reply]` is just a wrapper around `State => (Seq[Event], Reply)`,
you can think of it as `Kleisli[(Seq[Event], ?), State, Reply]`
i.e. a side-effecting function with a side effect being a sequence of events representing state change caused by operation.

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

def handler[F[_]](implicit F: Applicative[F]) = Lambda[SubscriptionOp ~> Handler[F, Option[Subscription], SubscriptionEvent, ?]] {
  case CreateSubscription(subscriptionId, userId, productId, planId) => {
    case Some(subscription) =>
      // Do nothing reply with ()
      F.pure(Seq.empty -> ())
    case None =>
      // Produce event and reply with ()
      F.pure(Seq(SubscriptionCreated(subscriptionId, userId, productId, planId)) -> ())
  }
  case PauseSubscription(subscriptionId) => {
    case Some(subscription) if subscription.status == Active =>
      F.pure(Seq(SubscriptionPaused(subscriptionId)) -> ())
    case _ =>
      F.pure(Seq.empty -> ())
  }
  case ResumeSubscription(subscriptionId) => {
    case Some(subscription) if subscription.status == Paused =>
      F.pure(Seq(SubscriptionResumed(subscriptionId)) -> ())
    case _ =>
      F.pure(Seq.empty -> ())
  }
  case CancelSubscription(subscriptionId) => {
    case Some(subscription) =>
      F.pure(Seq(SubscriptionCancelled(subscriptionId)) -> ())
    case _ =>
      F.pure(Seq.empty -> ())
  }
}
```

Then you define a correlation function, entity name and a value provided by correlation function form unique primary key for aggregate.
It should not be changed between releases, at least without prior event migration.

```scala
def correlation: Correlation[SubscriptionOp] = _.subscriptionId
```

After that we are ready to launch.

```scala
import monix.cats._
import monix.eval.Task
import aecor.effect.monix._
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime

val system = ActorSystem("foo")

val subscriptions: SubscriptionOp ~> Task =
  AkkaPersistenceRuntime(
    system,
    "Subscription",
    correlation,
    EventsourcedBehavior(
      handler,
      Subscription.folder
    ),
    Tagging.const[SubscriptionEvent](EventTag("Payment"))
  )
```
