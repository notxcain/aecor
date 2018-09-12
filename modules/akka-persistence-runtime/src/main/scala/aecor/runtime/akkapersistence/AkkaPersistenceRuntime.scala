package aecor.runtime.akkapersistence

import java.nio.ByteBuffer

import aecor.data.Tagging
import aecor.data.next.EventsourcedBehavior
import aecor.encoding.{KeyDecoder, KeyEncoder}
import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.{Decoder, Encoded, Encoder}
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime._
import aecor.runtime.akkapersistence.AkkaPersistenceRuntimeActor.CommandResult
import aecor.runtime.akkapersistence.readside.{AkkaPersistenceEventJournalQuery, JournalQuery}
import aecor.runtime.akkapersistence.serialization.{Message, PersistentDecoder, PersistentEncoder}
import aecor.util.effect._
import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.pattern.ask
import akka.util.Timeout
import cats.data.EitherT
import cats.effect.Effect
import cats.~>
import io.aecor.liberator.syntax._
import cats.implicits._

object AkkaPersistenceRuntime {
  def apply[O](system: ActorSystem, journalAdapter: JournalAdapter[O]): AkkaPersistenceRuntime[O] =
    new AkkaPersistenceRuntime(system, journalAdapter)

  private[akkapersistence] final case class EntityCommand(entityKey: String,
                                                          commandBytes: ByteBuffer)
      extends Message
}

class AkkaPersistenceRuntime[O] private[akkapersistence] (system: ActorSystem,
                                                          journalAdapter: JournalAdapter[O]) {
  def deploy[M[_[_]], F[_], State, Event: PersistentEncoder: PersistentDecoder, Key: KeyEncoder: KeyDecoder, Rejection](
    typeName: String,
    behavior: EventsourcedBehavior[M, F, State, Event, Rejection],
    tagging: Tagging[Key],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaPersistenceRuntimeSettings = AkkaPersistenceRuntimeSettings.default(system)
  )(implicit M: WireProtocol[M], F: Effect[F], rejectionDecoder: Decoder[Rejection], rejectionEncoder: Encoder[Rejection]): F[Key => M[EitherT[F, Rejection, ?]]] =
    F.delay {
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

      val keyEncoder = KeyEncoder[Key]

      key =>
        M.encoder.mapK(new (Encoded ~> EitherT[F, Rejection, ?]) {
          override def apply[A](fa: (ByteBuffer, WireProtocol.Decoder[A])): EitherT[F, Rejection, A] = EitherT {
            val (bytes, responseDecoder) = fa
            Effect[F]
              .fromFuture {
                regionRef ? EntityCommand(keyEncoder(key), bytes.asReadOnlyBuffer())
              }
              .map {
                case result: CommandResult =>
                  if (result.isRejection) {
                    rejectionDecoder.decode(result.rejectionBytes).map(Left(_))
                  } else {
                    responseDecoder.decode(result.resultBytes).map(Right(_))
                  }
              }
              .flatMap(F.fromEither(_))
          }
        })
    }

  def journal[K: KeyDecoder, E: PersistentDecoder]: JournalQuery[O, K, E] =
    AkkaPersistenceEventJournalQuery[O, K, E](journalAdapter)
}
