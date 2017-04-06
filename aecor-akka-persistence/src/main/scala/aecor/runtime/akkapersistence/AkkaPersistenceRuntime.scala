package aecor.runtime.akkapersistence

import aecor.aggregate._
import aecor.data.{ Correlation, Folded, Handler, Tagging }
import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime.CorrelatedCommand
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.implicits._
import cats.{ Monad, ~> }

import scala.collection.immutable.Seq
import scala.concurrent.Future

object AkkaPersistenceRuntime {
  def apply[F[_]: Async: CaptureFuture: Capture: Monad](
    system: ActorSystem
  ): AkkaPersistenceRuntime[F] =
    new AkkaPersistenceRuntime(system)

  private final case class CorrelatedCommand[C[_], A](entityId: String, command: C[A])
}

class AkkaPersistenceRuntime[F[_]: Async: CaptureFuture: Capture: Monad](system: ActorSystem) {
  def start[Op[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    behavior: Op ~> Handler[F, State, Seq[Event], ?],
    correlation: Correlation[Op],
    tagging: Tagging[Event],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaPersistenceRuntimeSettings = AkkaPersistenceRuntimeSettings.default(system)
  )(implicit folder: Folder[Folded, Event, State]): F[Op ~> F] = {

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
    }

    def startShardRegion = ClusterSharding(system).start(
      typeName = entityName,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    Capture[F].capture(startShardRegion).map { regionRef =>
      new (Op ~> F) {
        implicit private val timeout = Timeout(settings.askTimeout)
        override def apply[A](fa: Op[A]): F[A] =
          CaptureFuture[F].captureF {
            (regionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
      }
    }
  }
}
