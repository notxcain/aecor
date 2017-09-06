package aecor.distributedprocessing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import aecor.distributedprocessing.DistributedProcessing.{ Process, ProcessKillSwitch }
import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import aecor.effect.{ Async, Capture }
import akka.actor.{ ActorSystem, SupervisorStrategy }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import akka.pattern.{ BackoffSupervisor, ask }
import akka.util.Timeout
import cats.Functor
import cats.implicits._
import scala.collection.immutable._
import scala.concurrent.duration.{ FiniteDuration, _ }

class DistributedProcessing(system: ActorSystem) {
  def start[F[_]: Functor: Async: Capture](name: String,
                                           processes: Seq[Process[F]],
                                           settings: DistributedProcessingSettings =
                                             DistributedProcessingSettings(
                                               minBackoff = 3.seconds,
                                               maxBackoff = 10.seconds,
                                               randomFactor = 0.2,
                                               shutdownTimeout = 10.seconds,
                                               numberOfShards = 100,
                                               heartbeatInterval = 2.seconds,
                                               clusterShardingSettings =
                                                 ClusterShardingSettings(system)
                                             )): F[ProcessKillSwitch[F]] =
    Capture[F].capture {

      val props = BackoffSupervisor.propsWithSupervisorStrategy(
        DistributedProcessingWorker.props(x => processes(x)),
        "worker",
        settings.minBackoff,
        settings.maxBackoff,
        settings.randomFactor,
        SupervisorStrategy.stoppingStrategy
      )

      val region = ClusterSharding(system).start(
        typeName = name,
        entityProps = props,
        settings = settings.clusterShardingSettings,
        extractEntityId = {
          case c @ KeepRunning(workerId) => (workerId.toString, c)
        },
        extractShardId = {
          case KeepRunning(workerId) => (workerId % settings.numberOfShards).toString
          case other                 => throw new IllegalArgumentException(s"Unexpected messge [$other]")
        }
      )

      val regionSupervisor = system.actorOf(
        DistributedProcessingSupervisor.props(processes.size, region, settings.heartbeatInterval),
        "DistributedProcessingSupervisor-" + URLEncoder.encode(name, StandardCharsets.UTF_8.name())
      )
      implicit val timeout = Timeout(settings.shutdownTimeout)
      ProcessKillSwitch {
        Capture[F].captureFuture {
          regionSupervisor ? DistributedProcessingSupervisor.GracefulShutdown
        }.void
      }
    }
}

object DistributedProcessing {
  def apply(system: ActorSystem): DistributedProcessing = new DistributedProcessing(system)
  final case class ProcessKillSwitch[F[_]](shutdown: F[Unit])
  final case class RunningProcess[F[_]](watchTermination: F[Unit], shutdown: () => Unit)
  final case class Process[F[_]](run: F[RunningProcess[F]])
}

final case class DistributedProcessingSettings(minBackoff: FiniteDuration,
                                               maxBackoff: FiniteDuration,
                                               randomFactor: Double,
                                               shutdownTimeout: FiniteDuration,
                                               numberOfShards: Int,
                                               heartbeatInterval: FiniteDuration,
                                               clusterShardingSettings: ClusterShardingSettings)
