package aecor.distributedprocessing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import aecor.distributedprocessing.DistributedProcessing.{ KillSwitch, Process }
import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import akka.actor.{ ActorSystem, SupervisorStrategy }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import akka.pattern.{ BackoffSupervisor, ask }
import akka.util.Timeout
import cats.effect.{ Effect, IO }
import cats.implicits._
import cats.{ Eval, Functor }

import scala.collection.immutable._
import scala.concurrent.duration.{ FiniteDuration, _ }

final class DistributedProcessing private (system: ActorSystem) {

  /**
    * Starts `processes` distributed over underlying akka cluster.
    *
    * @param name - type name of underlying cluster sharding
    * @param processes - list of processes to distribute
    *
    */
  def start[F[_]: Functor: Effect](name: String,
                                   processes: Seq[Process[F]],
                                   settings: DistributedProcessingSettings =
                                     DistributedProcessingSettings(
                                       minBackoff = 3.seconds,
                                       maxBackoff = 10.seconds,
                                       randomFactor = 0.2,
                                       shutdownTimeout = 10.seconds,
                                       numberOfShards = 100,
                                       heartbeatInterval = 2.seconds,
                                       clusterShardingSettings = ClusterShardingSettings(system)
                                     )): F[KillSwitch[F]] = {
    import system.dispatcher
    Effect[F].delay {
      val props = BackoffSupervisor.propsWithSupervisorStrategy(
        DistributedProcessingWorker.props(processes),
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
        IO.fromFuture(
            Eval.later(regionSupervisor ? DistributedProcessingSupervisor.GracefulShutdown)
          )
          .void
          .to[F]
      }
    }
  }
}

object DistributedProcessing {
  def apply(system: ActorSystem): DistributedProcessing = new DistributedProcessing(system)
  final case class KillSwitch[F[_]](shutdown: F[Unit]) extends AnyVal
  final case class RunningProcess[F[_]](watchTermination: F[Unit], shutdown: () => Unit)
  final case class Process[F[_]](run: F[RunningProcess[F]]) extends AnyVal
}

final case class DistributedProcessingSettings(minBackoff: FiniteDuration,
                                               maxBackoff: FiniteDuration,
                                               randomFactor: Double,
                                               shutdownTimeout: FiniteDuration,
                                               numberOfShards: Int,
                                               heartbeatInterval: FiniteDuration,
                                               clusterShardingSettings: ClusterShardingSettings)
