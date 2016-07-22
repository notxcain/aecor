package aecor.core.message

import akka.actor.{Actor, ActorLogging, ReceiveTimeout}
import aecor.core.message.Passivation._
import akka.cluster.sharding.ShardRegion.Passivate

import scala.concurrent.duration.FiniteDuration

object Passivation {
  private case object Stop
}

trait Passivation { this: Actor with ActorLogging =>
  def idleTimeout: FiniteDuration
  def shouldPassivate: Boolean = true

  final protected def setIdleTimeout(): Unit = {
    log.debug("Setting idle timeout to [{}]", idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }

  final protected def receivePassivationMessages: Receive = {
    case ReceiveTimeout ⇒
      if (shouldPassivate) {
        passivate()
      } else {
        setIdleTimeout()
      }
    case Stop ⇒
      context.stop(self)
  }

  private def passivate(): Unit = {
    log.debug("Passivating...")
    context.parent ! Passivate(stopMessage = Stop)
  }
}
