package aecor.runtime.akkapersistence

import aecor.data.{ EventsourcedBehavior, Tagging }
import aecor.encoding.WireProtocol.Encoded
import aecor.encoding.syntax._
import aecor.encoding.{ KeyDecoder, KeyEncoder, WireProtocol }
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime._
import aecor.runtime.akkapersistence.AkkaPersistenceRuntimeActor.CommandResult
import aecor.runtime.akkapersistence.readside.{ AkkaPersistenceEventJournalQuery, JournalQuery }
import aecor.runtime.akkapersistence.serialization.{ Message, PersistentDecoder, PersistentEncoder }
import aecor.util.effect._
import akka.actor.ActorSystem
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.Effect
import cats.implicits._
import cats.tagless.FunctorK
import cats.~>
import cats.tagless.syntax.functorK._
import scodec.bits.BitVector

object AkkaPersistenceRuntime {
  def apply[O](system: ActorSystem, journalAdapter: JournalAdapter[O]): AkkaPersistenceRuntime[O] =
    new AkkaPersistenceRuntime(system, journalAdapter)

  private[akkapersistence] final case class EntityCommand(entityKey: String,
                                                          commandBytes: BitVector)
      extends Message
}

class AkkaPersistenceRuntime[O] private[akkapersistence] (system: ActorSystem,
                                                          journalAdapter: JournalAdapter[O]) {
  def deploy[M[_[_]]: FunctorK, F[_], State, Event: PersistentEncoder: PersistentDecoder, Key: KeyEncoder: KeyDecoder](
    typeName: String,
    behavior: EventsourcedBehavior[M, F, State, Event],
    tagging: Tagging[Key],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    settings: AkkaPersistenceRuntimeSettings = AkkaPersistenceRuntimeSettings.default(system)
  )(implicit M: WireProtocol[M], F: Effect[F]): F[Key => M[F]] =
    F.delay {
      val props =
        AkkaPersistenceRuntimeActor.props(
          typeName,
          behavior.actions,
          behavior.create,
          behavior.update,
          snapshotPolicy,
          tagging,
          settings.idleTimeout,
          journalAdapter.writeJournalId,
          snapshotPolicy.pluginId
        )

      val extractEntityId: ShardRegion.ExtractEntityId = {
        case EntityCommand(entityId, bytes) =>
          (entityId, AkkaPersistenceRuntimeActor.HandleCommand(bytes))
      }

      val numberOfShards = settings.numberOfShards

      val extractShardId: ShardRegion.ExtractShardId = {
        case EntityCommand(entityId, _) =>
          (scala.math.abs(entityId.hashCode) % numberOfShards).toString
        case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
      }

      val shardRegion = ClusterSharding(system).start(
        typeName = typeName,
        entityProps = props,
        settings = settings.clusterShardingSettings,
        extractEntityId = extractEntityId,
        extractShardId = extractShardId
      )

      val keyEncoder = KeyEncoder[Key]

      key =>
        M.encoder.mapK(new (Encoded ~> F) {

          implicit val askTimeout: Timeout = Timeout(settings.askTimeout)

          override def apply[A](fa: Encoded[A]): F[A] = F.suspend {
            val (bytes, decoder) = fa
            F.fromFuture {
                shardRegion ? EntityCommand(keyEncoder(key), bytes)
              }
              .flatMap {
                case CommandResult(resultBytes) =>
                  decoder.decodeValue(resultBytes).lift[F]
                case other =>
                  F.raiseError(
                    new IllegalArgumentException(s"Unexpected response [$other] from shard region")
                  )
              }
          }
        })
    }

  def journal[K: KeyDecoder, E: PersistentDecoder]: JournalQuery[O, K, E] =
    AkkaPersistenceEventJournalQuery[O, K, E](journalAdapter)
}
