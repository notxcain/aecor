package aecor.distributedprocessing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import aecor.distributedprocessing.DistributedProcessing.{ KillSwitch, Process }
import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import akka.actor.{ ActorSystem, SupervisorStrategy }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import akka.pattern.{ BackoffSupervisor, ask }
import akka.util.Timeout
import cats.effect.Effect
import cats.implicits._

import scala.concurrent.duration.{ FiniteDuration, _ }
import aecor.util.effect._
final class DistributedProcessing private (system: ActorSystem) {

  /**
    * Starts `processes` distributed over underlying akka cluster.
    *
    * @param name - type name of underlying cluster sharding
    * @param processes - list of processes to distribute
    *
    */
  def start[F[_]: Effect](name: String,
                          processes: List[Process[F]],
                          settings: DistributedProcessingSettings = DistributedProcessingSettings.default(system)): F[KillSwitch[F]] =
    Effect[F].delay {
      val props = BackoffSupervisor.propsWithSupervisorStrategy(
        DistributedProcessingWorker.props(processes, name),
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
          case other                 => throw new IllegalArgumentException(s"Unexpected message [$other]")
        }
      )

      val regionSupervisor = system.actorOf(
        DistributedProcessingSupervisor
          .props(processes.size, region, settings.heartbeatInterval),
        "DistributedProcessingSupervisor-" + URLEncoder
          .encode(name, StandardCharsets.UTF_8.name())
      )
      implicit val timeout = Timeout(settings.shutdownTimeout)
      KillSwitch {
        Effect[F].fromFuture {
          regionSupervisor ? DistributedProcessingSupervisor.GracefulShutdown
        }.void
      }
    }
}

object DistributedProcessing {
  def apply(system: ActorSystem): DistributedProcessing = new DistributedProcessing(system)
  final case class KillSwitch[F[_]](shutdown: F[Unit]) extends AnyVal
  final case class RunningProcess[F[_]](watchTermination: F[Unit], shutdown: F[Unit])
  final case class Process[F[_]](run: F[RunningProcess[F]]) extends AnyVal
}

final case class DistributedProcessingSettings(minBackoff: FiniteDuration,
                                               maxBackoff: FiniteDuration,
                                               randomFactor: Double,
                                               shutdownTimeout: FiniteDuration,
                                               numberOfShards: Int,
                                               heartbeatInterval: FiniteDuration,
                                               clusterShardingSettings: ClusterShardingSettings)

object DistributedProcessingSettings {
  def default(clusterShardingSettings: ClusterShardingSettings): DistributedProcessingSettings =
    DistributedProcessingSettings(
      minBackoff = 3.seconds,
      maxBackoff = 10.seconds,
      randomFactor = 0.2,
      shutdownTimeout = 10.seconds,
      numberOfShards = 100,
      heartbeatInterval = 2.seconds,
      clusterShardingSettings = clusterShardingSettings
    )

  def default(system: ActorSystem): DistributedProcessingSettings =
    default(ClusterShardingSettings(system))
}
