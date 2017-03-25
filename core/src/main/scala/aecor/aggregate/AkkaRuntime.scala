package aecor.aggregate

import aecor.aggregate.AkkaRuntime.CorrelatedCommand
import aecor.aggregate.runtime.{ Async, Capture, CaptureFuture }
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.data.{ Folded, Handler }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.{ Monad, ~> }
import cats.implicits._

import scala.collection.immutable.Seq
import scala.concurrent.Future

object AkkaRuntime {
  def apply[F[_]: Async: CaptureFuture: Capture: Monad](system: ActorSystem): AkkaRuntime[F] =
    new AkkaRuntime(system)

  private final case class CorrelatedCommand[C[_], A](entityId: String, command: C[A])
}

class AkkaRuntime[F[_]: Async: CaptureFuture: Capture: Monad](system: ActorSystem) {
  def start[Command[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    behavior: Command ~> Handler[F, State, Seq[Event], ?],
    correlation: Correlation[Command],
    tagging: Tagging[Event],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaRuntimeSettings = AkkaRuntimeSettings.default(system)
  )(implicit folder: Folder[Folded, Event, State]): F[Command ~> F] = {

    val props =
      AggregateActor.props(entityName, behavior, snapshotPolicy, tagging, settings.idleTimeout)

    def extractEntityId: ShardRegion.ExtractEntityId = {
      case CorrelatedCommand(entityId, c) =>
        (entityId, AggregateActor.HandleCommand(c))
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
      new (Command ~> F) {
        implicit private val timeout = Timeout(settings.askTimeout)
        override def apply[A](fa: Command[A]): F[A] =
          CaptureFuture[F].captureF {
            (regionRef ? CorrelatedCommand(correlation(fa), fa)).asInstanceOf[Future[A]]
          }
      }
    }
  }
}
