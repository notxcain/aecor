package aecor.streaming.process

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.streaming.process.DistributedProcessing.{ RunningProcess, ProcessKillSwitch }
import aecor.streaming.process.DistributedProcessingWorker.KeepRunning
import akka.actor.{ ActorSystem, SupervisorStrategy }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.pattern.BackoffSupervisor
import cats.Functor

import scala.concurrent.duration.{ FiniteDuration, _ }

class DistributedProcessing(system: ActorSystem) {

  def start[F[_]: Async: Capture: CaptureFuture: Functor](
    name: String,
    workerCount: Int,
    processForWorker: Int => F[RunningProcess[F]],
    settings: DistributedProcessingSettings = DistributedProcessingSettings(
      minBackoff = 3.seconds,
      maxBackoff = 10.seconds,
      randomFactor = 0.2,
      shutdownTimeout = 10.seconds,
      numberOfShards = 100,
      heartbeatInterval = 2.seconds,
      clusterShardingSettings = ClusterShardingSettings(system)
    )
  ): F[ProcessKillSwitch[F]] = Capture[F].capture {

    val props = BackoffSupervisor.propsWithSupervisorStrategy(
      DistributedProcessingWorker.props(processForWorker),
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
      }
    )

    system.actorOf(
      DistributedProcessingSupervisor.props(workerCount, region, settings.heartbeatInterval),
      "DistributedProcessingSupervisor-" + URLEncoder.encode(name, StandardCharsets.UTF_8.name())
    )

    ProcessKillSwitch {
      Capture[F].capture {
        region ! ShardRegion.GracefulShutdown
      }
    }
  }
}

object DistributedProcessing {
  def apply(system: ActorSystem): DistributedProcessing = new DistributedProcessing(system)
  final case class ProcessKillSwitch[F[_]](shutdown: F[Unit])
  final case class RunningProcess[F[_]](watchTermination: F[Unit], terminate: () => Unit)
}

final case class DistributedProcessingSettings(minBackoff: FiniteDuration,
                                               maxBackoff: FiniteDuration,
                                               randomFactor: Double,
                                               shutdownTimeout: FiniteDuration,
                                               numberOfShards: Int,
                                               heartbeatInterval: FiniteDuration,
                                               clusterShardingSettings: ClusterShardingSettings)
