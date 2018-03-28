package aecor.runtime.akkapersistence

import java.nio.ByteBuffer

import aecor.data._
import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Encoded
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime._
import aecor.runtime.akkapersistence.readside.{ AkkaPersistenceEventJournalQuery, JournalQuery }
import aecor.runtime.akkapersistence.serialization.{ Message, PersistentDecoder, PersistentEncoder }
import aecor.util.effect._
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.Effect
import cats.~>
import io.aecor.liberator.syntax._

import scala.concurrent.Future

object AkkaPersistenceRuntime {
  def apply[O](system: ActorSystem, journalAdapter: JournalAdapter[O]): AkkaPersistenceRuntime[O] =
    new AkkaPersistenceRuntime(system, journalAdapter)

  private[akkapersistence] final case class EntityCommand(entityKey: String,
                                                          commandBytes: ByteBuffer)
      extends Message
}

class AkkaPersistenceRuntime[O] private[akkapersistence] (system: ActorSystem,
                                                          journalAdapter: JournalAdapter[O]) {
  def deploy[M[_[_]], F[_]: Effect, State, Event: PersistentEncoder: PersistentDecoder, K: KeyEncoder: KeyDecoder](
    typeName: String,
    behavior: EventsourcedBehaviorT[M, F, State, Event],
    tagging: Tagging[K],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaPersistenceRuntimeSettings = AkkaPersistenceRuntimeSettings.default(system)
  )(implicit M: WireProtocol[M]): F[K => M[F]] =
    Effect[F].delay {
      import system.dispatcher
      val props =
        AkkaPersistenceRuntimeActor.props(
          typeName,
          behavior.actions,
          behavior.initialState,
          behavior.applyEvent,
          snapshotPolicy,
          tagging,
          settings.idleTimeout,
          journalAdapter.writeJournalId,
          snapshotPolicy.pluginId
        )

      def extractEntityId: ShardRegion.ExtractEntityId = {
        case EntityCommand(entityId, bytes) =>
          (entityId, AkkaPersistenceRuntimeActor.HandleCommand(bytes))
      }

      val numberOfShards = settings.numberOfShards

      def extractShardId: ShardRegion.ExtractShardId = {
        case EntityCommand(entityId, _) =>
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

      val keyEncoder = KeyEncoder[K]

      key =>
        M.encoder.mapK(new (Encoded ~> F) {
          override def apply[A](fa: (ByteBuffer, WireProtocol.Decoder[A])): F[A] =
            Effect[F].fromFuture {
              val (bytes, responseDecoder) = fa
              (regionRef ? EntityCommand(keyEncoder(key), bytes.asReadOnlyBuffer()))
                .asInstanceOf[Future[ByteBuffer]]
                .map(responseDecoder.decode)
                .flatMap {
                  case Right(value) => Future.successful(value)
                  case Left(error)  => Future.failed(error)
                }
            }
        })
    }

  def journal[K: KeyDecoder, E: PersistentDecoder]: JournalQuery[O, K, E] =
    AkkaPersistenceEventJournalQuery[O, K, E](journalAdapter)
}
