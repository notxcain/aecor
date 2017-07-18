package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data._
import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.{ Monad, ~> }
import cats.implicits._

import scala.concurrent.Future

object AkkaPersistenceRuntime {
  def apply[F[_]: Async: CaptureFuture: Capture: Monad](
    system: ActorSystem
  ): AkkaPersistenceRuntime[F] =
    new AkkaPersistenceRuntime(system)

  private final case class CorrelatedCommand[C[_], A](entityId: String, command: C[A])
}

class AkkaPersistenceRuntime[F[_]: Async: CaptureFuture: Capture: Monad](system: ActorSystem) {
  import AkkaPersistenceRuntime._

  private val pureUnit = Monad[F].pure(())

  def start[Op[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    correlation: Correlation[Op],
    behavior: EventsourcedBehavior[F, Op, State, Event],
    tagging: Tagging[Event],
    onPersist: Event => F[Unit] = (_: Event) => pureUnit,
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaPersistenceRuntimeSettings = AkkaPersistenceRuntimeSettings.default(system)
  ): F[Op ~> F] = {

    val props =
      AkkaPersistenceRuntimeActor.props(
        entityName,
        behavior,
        snapshotPolicy,
        tagging,
        onPersist,
        settings.idleTimeout
      )

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case CorrelatedCommand(entityId, c) =>
        (entityId, AkkaPersistenceRuntimeActor.HandleCommand(c))
    }

    val numberOfShards = settings.numberOfShards

    val extractShardId: ShardRegion.ExtractShardId = {
      case CorrelatedCommand(entityId, _) =>
        (scala.math.abs(entityId.hashCode) % numberOfShards).toString
      case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
    }

    val startShardRegion = Capture[F].capture {
      ClusterSharding(system).start(
        typeName = entityName,
        entityProps = props,
        settings = settings.clusterShardingSettings,
        extractEntityId = extractEntityId,
        extractShardId = extractShardId
      )
    }

    startShardRegion.map { regionRef =>
      new (Op ~> F) {
        implicit private val timeout = Timeout(settings.askTimeout)
        override def apply[A](fa: Op[A]): F[A] =
          CaptureFuture[F].captureFuture {
            (regionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
      }
    }
  }
}

object AkkaPersistenceRuntime2 {
  def apply[F[_]: Async: CaptureFuture: Capture: Monad, Op[_], State, Event: PersistentEncoder: PersistentDecoder](
    system: ActorSystem,
    entityName: String,
    correlation: Correlation[Op],
    behavior: EventsourcedBehavior[F, Op, State, Event],
    tagging: Tagging[Event],
    onPersist: Option[Event => F[Unit]] = None,
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: Option[AkkaPersistenceRuntimeSettings] = None
  ): AkkaPersistenceRuntime2[F, Op, State, Event] = {
    val pureUnit = Monad[F].pure(())
    new AkkaPersistenceRuntime2[F, Op, State, Event](
      system,
      entityName,
      correlation,
      behavior,
      tagging,
      onPersist.getOrElse(_ => pureUnit),
      snapshotPolicy,
      settings
    )
  }

  private final case class CorrelatedCommand[C[_], A](entityId: String, command: C[A])
}

class AkkaPersistenceRuntime2[F[_]: Async: CaptureFuture: Capture: Monad, Op[_], State, Event: PersistentEncoder: PersistentDecoder](
  system: ActorSystem,
  entityName: String,
  correlation: Correlation[Op],
  behavior: EventsourcedBehavior[F, Op, State, Event],
  tagging: Tagging[Event],
  onPersisted: Event => F[Unit],
  snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
  customSettings: Option[AkkaPersistenceRuntimeSettings] = None
) {

  import AkkaPersistenceRuntime2._
  def start: F[Op ~> F] = {
    val settings = customSettings.getOrElse(AkkaPersistenceRuntimeSettings.default(system))
    val props =
      AkkaPersistenceRuntimeActor.props(
        entityName,
        behavior,
        snapshotPolicy,
        tagging,
        onPersisted,
        settings.idleTimeout
      )

    def extractEntityId: ShardRegion.ExtractEntityId = {
      case CorrelatedCommand(entityId, c) =>
        (entityId, AkkaPersistenceRuntimeActor.HandleCommand(c))
    }

    val numberOfShards = settings.numberOfShards

    def extractShardId: ShardRegion.ExtractShardId = {
      case CorrelatedCommand(entityId, _) =>
        (scala.math.abs(entityId.hashCode) % numberOfShards).toString
      case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
    }

    def startShardRegion = ClusterSharding(system).start(
      typeName = entityName,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    Capture[F].capture {
      val regionRef = startShardRegion
      new (Op ~> F) {
        implicit private val timeout = Timeout(settings.askTimeout)
        override def apply[A](fa: Op[A]): F[A] =
          CaptureFuture[F].captureFuture {
            (regionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
      }
    }
  }

  def journal: EventJournalQuery[UUID, Event] = CassandraEventJournalQuery[Event](system)
}
