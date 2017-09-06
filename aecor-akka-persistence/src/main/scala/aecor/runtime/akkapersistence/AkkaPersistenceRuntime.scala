package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data._
import aecor.effect.{ Async, Capture }
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.{ Monad, ~> }

import scala.concurrent.Future

final case class AkkaPersistenceRuntimeUnit[F[_], Op[_], State, Event](
  entityName: String,
  correlation: Correlation[Op],
  behavior: EventsourcedBehavior[F, Op, State, Event],
  tagging: Tagging[Event],
  snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never
)

abstract class AkkaPersistenceRuntimeDeployment[F[_], Op[_], Event] {
  def start: F[Op ~> F]
  def journal: EventJournalQuery[UUID, Event]
}

private class DefaultAkkaPersistenceRuntimeDeployment[F[_]: Async: Capture: Monad, Op[_], State, Event: PersistentEncoder: PersistentDecoder](
  system: ActorSystem,
  unit: AkkaPersistenceRuntimeUnit[F, Op, State, Event],
  settings: AkkaPersistenceRuntimeSettings
) extends AkkaPersistenceRuntimeDeployment[F, Op, Event] {
  import AkkaPersistenceRuntime._
  import unit._

  def start: F[Op ~> F] = {
    val props =
      AkkaPersistenceRuntimeActor.props(
        entityName,
        behavior,
        snapshotPolicy,
        tagging,
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
          Capture[F].captureFuture {
            (regionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
      }
    }
  }

  def journal: EventJournalQuery[UUID, Event] = CassandraEventJournalQuery[Event](system)
}

object AkkaPersistenceRuntime {
  def apply(system: ActorSystem): AkkaPersistenceRuntime = {
    val settings = AkkaPersistenceRuntimeSettings.default(system)
    new AkkaPersistenceRuntime(system, settings)
  }

  def apply(system: ActorSystem, settings: AkkaPersistenceRuntimeSettings): AkkaPersistenceRuntime =
    new AkkaPersistenceRuntime(system, settings)

  private[akkapersistence] final case class CorrelatedCommand[C[_], A](entityId: String,
                                                                       command: C[A])
}

class AkkaPersistenceRuntime(system: ActorSystem, settings: AkkaPersistenceRuntimeSettings) {
  def deploy[F[_]: Async: Capture: Monad, Op[_], State, Event: PersistentEncoder: PersistentDecoder](
    unit: AkkaPersistenceRuntimeUnit[F, Op, State, Event]
  ): AkkaPersistenceRuntimeDeployment[F, Op, Event] =
    new DefaultAkkaPersistenceRuntimeDeployment[F, Op, State, Event](system, unit, settings)
}
