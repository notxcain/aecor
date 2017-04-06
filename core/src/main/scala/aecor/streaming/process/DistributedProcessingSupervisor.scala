package aecor.streaming.process

import aecor.streaming.process.DistributedProcessingSupervisor.Tick
import aecor.streaming.process.DistributedProcessingWorker.KeepRunning
import akka.actor.{ Actor, ActorRef, Props, Terminated }

import scala.concurrent.duration.{ FiniteDuration, _ }

object DistributedProcessingSupervisor {
  final case class KeepAlive(workerId: Int)
  final case object Tick
  def props(processCount: Int, shardRegion: ActorRef, heartbeatInterval: FiniteDuration): Props =
    Props(new DistributedProcessingSupervisor(processCount, shardRegion, heartbeatInterval))
}

final class DistributedProcessingSupervisor(processCount: Int,
                                            shardRegion: ActorRef,
                                            heartbeatInterval: FiniteDuration)
    extends Actor {

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
      (0 to processCount).foreach { processId =>
        shardRegion ! KeepRunning(processId)
      }
    case Terminated(`shardRegion`) =>
      context.stop(self)
  }
}
