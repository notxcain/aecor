
[![Build Status](https://img.shields.io/travis/notxcain/aecor/master.svg)](https://travis-ci.org/notxcain/aecor)
[![Maven Central](https://img.shields.io/maven-central/v/io.aecor/core_2.12.svg)](https://search.maven.org/search?q=g:io.aecor%20AND%20a:core_2.12&core=gav)
[![Join the chat at https://gitter.im/notxcain/aecor](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/notxcain/aecor)


# Aecor
### A pure functional library for defining and running eventsourced behaviors

Aecor is an opinionated library to help building scalable, distributed eventsourced services written in Scala.

Built-in runtime implementation uses [Akka](https://github.com/akka/akka) for distribution and fault tolerance.

It heavily relies on [Cats](https://github.com/typelevel/cats) and [Cats Effect](https://github.com/typelevel/cats-effect)

Aecor works on Scala 2.12 with Java 8.

The name `Aecor` (_lat. ocean_) is inspired by a vision of modern distributed applications as an ocean of messages with pure behaviors floating in it.
    
### Installing Aecor

To start using Aecor Akka Persistence Runtime add the following to your `build.sbt` file:

```scala
scalaVersion := "2.12.4"
scalacOptions += "-Ypartial-unification"
addCompilerPlugin("org.scalameta" % "paradise" % "3.0.0-M10" cross CrossVersion.full)
libraryDependencies += "io.aecor" %% "akka-peristence-runtime" % "x.y.z" // See current version on the badge above
```

### Entity Behavior Definition

In this short guide I'll show you how to define and deploy your first event sourced behavior on runtime backed by Akka Persistence and Akka Cluster Sharding.

Each entity needs an identity, so let's start with identifier type:

```scala
final case class SubscriptionId(value: java.util.UUID) extends AnyVal
```

Then define what actions we're able to perform on `Subscription`

```scala
import aecor.macros.boopickleWireProtocol
import cats.tagless.autoFunctorK
import boopickle.Default._

@boopickleWireProtocol
@autoFunctorK(false)
trait Subscription[F[_]] {
  def createSubscription(userId: String, productId: String, planId: String): F[Unit]
  def pauseSubscription: F[Unit]
  def resumeSubscription: F[Unit]
  def cancelSubscription: F[Unit]
}
```

You may notice that there is no `SubscriptionId` involved, and it's okay because this interface describes actions of a concrete `Subscription` and entity behavior should not know about its identity, it's behavior should be defined solely by its state.

There is an abstract type `F[_]` which stays for an effect (see [Rob Norris, Functional Programming with Effects](https://www.youtube.com/watch?v=po3wmq4S15A)) that would be performed during each action invocation.

Also being polymorphic in effect improves the reuse of this interface, you'll see it later.

`@boopickleWireProtocol` - is a macro annotation that automates derivation of a `WireProtocol`, which is used by Akka Runtime to encode and decode actions and corresponding responses.

We are event sourced, so let's define our events:

```scala
import aecor.runtime.akkapersistence.serialization._

sealed abstract class SubscriptionEvent extends Product with Serializable
object SubscriptionEvent {
  final case class SubscriptionCreated(userId: String, productId: String, planId: String) extends SubscriptionEvent
  final case object SubscriptionPaused extends SubscriptionEvent
  final case object SubscriptionResumed extends SubscriptionEvent
  final case object SubscriptionCancelled extends SubscriptionEvent

  implicit val persistentEncoder: PersistentEncoder[SubscriptionEvent] = ???
  implicit val persistentDecoder: PersistentDecoder[SubscriptionEvent] = ???
}
```

I've intentionally omitted implementation of `PersistentEncoder` and `PersistentDecoder`, because providing generic JSON encoding would be careless as persistent event schema requires your attention and I would recommend to use Protobuf or other formats that support schema evolution.

Let's define a state on which `Subscription` operates.

```scala
import aecor.data.Folded.syntax._
import SubscriptionState._

final case class SubscriptionState(status: Status) {
  def update(e: SubscriptionEvent): Folded[Subscription] = e match {
    case SubscriptionCreated(_, _, _) =>
      impossible
    case SubscriptionPaused =>
      subscription.copy(status = Paused).next
    case SubscriptionResumed =>
      subscription.copy(status = Active).next
    case SubscriptionCancelled =>
      subscription.copy(status = Cancelled).next
  }
}

object SubscriptionState {
  def create(e: SubscriptionEvent): Folded[SubscriptionState] = e match {
    case SubscriptionCreated(userId, productId, planId) =>
      Subscription(Active).next
    case _ => impossible
  }
  sealed abstract class Status extends Product with Serializable
    object Status {
      final case object Active extends Status
      final case object Paused extends Status
      final case object Cancelled extends Status
    }
}

```

Pay attention to `Folded` datatype, it has to constructor:
- `Impossible` is used to express impossible folds of events, so that you don't throw exceptions.
- `Next(a: A)` is used to express successful event application.


Now, the final part before we launch.'

As I said earlier `Subscription[F[_]]` is polymorphic in its effect type.

Our effect would be any `F[_]` with instance of `MonadAction[F, Option[SubscriptionState], SubscriptionEvent]` which provides essential operations for eventsources command handler
* `read: F[Option[SubscriptionState]]` - reads current state
* `append(event: SubscriptionEvent, other: SubscriptionEvent*): F[Unit]` - append one or more events
Other stuff like state recovery and event persistence is held by Akka Persistence Runtime.

So lets define `SubscriptionActions`

```scala
import cats.implicits._ // needed for syntax like flatMap and unit

final class SubscriptionActions[F[_]](
  implicit F: MonadAction[F, Option[SubscriptionState], SubscriptionEvent]
  ) extends Subscription[F] {

  import F._ // import algebra functions

  def createSubscription(userId: String, productId: String, planId: String): F[Unit] =
    read.flatMap {
      case Some(subscription) =>
        // Do nothing reply with ()
        unit
      case None =>
        // Produce event and reply with ()
        append(SubscriptionCreated(userId, productId, planId))
    }

  def pauseSubscription: F[Unit] =
    read.flatMap {
      case Some(subscription) if subscription.status == Active =>
        append(SubscriptionPaused)
      case _ =>
        unit
    }

  def resumeSubscription: F[Unit] =
    read.flatMap {
      case Some(subscription) if subscription.status == Paused =>
        append(SubscriptionResumed)
      case _ =>
        unit
    }
   
  def cancelSubscription: F[Unit] =
    read.flatMap {
      case Some(subscription) if subscription.canCancel =>
        append(SubscriptionCancelled)
      case _ =>
        unit
    }
}
```

Now when actions are defined we're ready to deploy

```scala

import cats.effect.IO
import aecor.runtime.akkapersistence._

val system = ActorSystem("system")

val journalAdapter = CassandraJournalAdapter(system)

val runtime = AkkaPersistenceRuntime(system, journalAdapter)

val behavior: EventsourcedBehavior[Subscription, IO, Option[SubscriptionState], SubscriptionEvent]  
  EventsourcedBehavior.optional(
    new SubscriptionActions,
    SubscriptionState.create,
    _.update(_)
  )

val deploySubscriptions: IO[SubscriptionId => Subscription[IO]] =
  runtime.deploy(
    "Subscription",
    behavior,
    Tagging.const[SubscriptionId](EventTag("Subscription"))
  )
```

# Projections

```
val journalQuery = runtime.journal
```
