package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data._
import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime._
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.util.effect._
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.Effect
import cats.~>

import scala.concurrent.Future

object AkkaPersistenceRuntime {
  def apply(system: ActorSystem): AkkaPersistenceRuntime =
    new AkkaPersistenceRuntime(system)

  private[akkapersistence] final case class CorrelatedCommand[C[_], A](entityId: String,
                                                                       command: C[A])
}

class AkkaPersistenceRuntime private[akkapersistence] (system: ActorSystem) {
  def deploy[F[_]: Effect, I: KeyEncoder: KeyDecoder, Op[_], State, Event: PersistentEncoder: PersistentDecoder](
    typeName: String,
    behavior: EventsourcedBehaviorT[F, Op, State, Event],
    tagging: Tagging[I],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaPersistenceRuntimeSettings = AkkaPersistenceRuntimeSettings.default(system)
  ): F[I => Op ~> F] =
    Effect[F].delay {
      import system.dispatcher
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
            Effect[F].fromFuture {
              (regionRef ? CorrelatedCommand(keyEncoder(i), fa)).asInstanceOf[Future[A]]
            }
        }
    }

  def journal[I: KeyDecoder, Event: PersistentDecoder]: EventJournal[UUID, I, Event] =
    CassandraEventJournal[I, Event](system)
}
