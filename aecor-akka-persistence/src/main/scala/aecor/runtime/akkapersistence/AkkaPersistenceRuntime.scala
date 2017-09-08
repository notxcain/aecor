package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data._
import aecor.effect.{ Async, Capture }
import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.{ Monad, ~> }

import scala.concurrent.Future

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

class AkkaPersistenceRuntime private[akkapersistence] (system: ActorSystem,
                                                       settings: AkkaPersistenceRuntimeSettings) {
  def deploy[F[_]: Async: Capture: Monad, I: KeyEncoder: KeyDecoder, Op[_], State, Event: PersistentEncoder: PersistentDecoder](
    unit: AkkaPersistenceRuntimeUnit[F, I, Op, State, Event]
  ): Deployment[F, I, Op, Event] =
    new DefaultDeployment[F, I, Op, State, Event](system, unit, settings)
}

final case class AkkaPersistenceRuntimeUnit[F[_], I, Op[_], State, Event](
  typeName: String,
  behavior: EventsourcedBehavior[F, Op, State, Event],
  tagging: Tagging[I],
  snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never
)

abstract class Deployment[F[_], I, Op[_], Event] {
  def start: F[I => Op ~> F]
  def journal: EventJournalQuery[UUID, I, Event]
}

private class DefaultDeployment[F[_]: Async: Capture: Monad, I: KeyEncoder: KeyDecoder, Op[_], State, Event: PersistentEncoder: PersistentDecoder](
  system: ActorSystem,
  unit: AkkaPersistenceRuntimeUnit[F, I, Op, State, Event],
  settings: AkkaPersistenceRuntimeSettings
) extends Deployment[F, I, Op, Event] {
  import AkkaPersistenceRuntime._
  import unit._

  def start: F[I => Op ~> F] =
    Capture[F].capture {
      val props =
        AkkaPersistenceRuntimeActor.props(
          typeName,
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

      val regionRef = ClusterSharding(system).start(
        typeName = typeName,
        entityProps = props,
        settings = settings.clusterShardingSettings,
        extractEntityId = extractEntityId,
        extractShardId = extractShardId
      )

      implicit val askTimeout = Timeout(settings.askTimeout)

      val keyEncoder = KeyEncoder[I]

      i =>
        new (Op ~> F) {
          override def apply[A](fa: Op[A]): F[A] =
            Capture[F].captureFuture {
              (regionRef ? CorrelatedCommand(keyEncoder(i), fa)).asInstanceOf[Future[A]]
            }
        }
    }

  def journal: EventJournalQuery[UUID, I, Event] = CassandraEventJournalQuery[I, Event](system)
}
