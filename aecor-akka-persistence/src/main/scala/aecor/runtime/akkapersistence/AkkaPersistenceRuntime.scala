package aecor.runtime.akkapersistence

import aecor.aggregate._
import aecor.data._
import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime.CorrelatedCommand
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.data.{ Kleisli, Nested, StateT }
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

final case class EventsourcedBehavior[F[_], Op[_], State, Event](
  handler: Op ~> Handler[F, State, Seq[Event], ?],
  folder: Folder[Folded, Event, State]
)

object EventsourcedBehavior {
  type Handler[F[_], State, Event, A] = Kleisli[PairT[F, Seq[Event], ?], State, ?]
  def Handler[F[_], State, Event, A](
    f: State => PairT[F, Seq[Event], A]
  ): Handler[F, State, Event, A] =
    Kleisli[PairT[F, Seq[Event], ?], State, A](f)
}

class AkkaPersistenceRuntime[F[_]: Async: CaptureFuture: Capture: Monad](system: ActorSystem) {
  def start[Op[_], State, Event: PersistentEncoder: PersistentDecoder](
    entityName: String,
    correlation: Correlation[Op],
    behavior: EventsourcedBehavior[F, Op, State, Event],
    tagging: Tagging[Event],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaPersistenceRuntimeSettings = AkkaPersistenceRuntimeSettings.default(system)
  ): F[Op ~> F] = {

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
}
