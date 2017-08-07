package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessingSupervisor.{
  GracefulShutdown,
  ShutdownCompleted,
  Tick
}
import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.cluster.sharding.ShardRegion

import scala.concurrent.duration.{ FiniteDuration, _ }

object DistributedProcessingSupervisor {
  private final case object Tick
  final case object GracefulShutdown
  final case object ShutdownCompleted

  def props(processCount: Int, shardRegion: ActorRef, heartbeatInterval: FiniteDuration): Props =
    Props(new DistributedProcessingSupervisor(processCount, shardRegion, heartbeatInterval))
}

final class DistributedProcessingSupervisor(processCount: Int,
                                            shardRegion: ActorRef,
                                            heartbeatInterval: FiniteDuration)
    extends Actor
    with ActorLogging {

  import context.dispatcher

  private val heartbeat =
    context.system.scheduler.schedule(0.seconds, heartbeatInterval, self, Tick)

  context.watch(shardRegion)

  override def postStop(): Unit = {
    heartbeat.cancel()
    ()
  }

  override def receive: Receive = {
    case Tick =>
      (0 until processCount).foreach { processId =>
        shardRegion ! KeepRunning(processId)
      }
    case Terminated(`shardRegion`) =>
      context.stop(self)
    case GracefulShutdown =>
      log.info(s"Performing graceful shutdown of [$shardRegion]")
      shardRegion ! ShardRegion.GracefulShutdown
      val replyTo = sender()
      context.become {
        case Terminated(`shardRegion`) =>
          log.info(s"Graceful shutdown completed for [$shardRegion]")
          context.stop(self)
          replyTo ! ShutdownCompleted
      }

  }
}
