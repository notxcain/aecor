package aecor.core.message

import akka.actor.{Actor, ReceiveTimeout}
import aecor.core.message.Passivation._
import akka.cluster.sharding.ShardRegion.Passivate

import scala.concurrent.duration.FiniteDuration

object Passivation {
  case object Stop
}

trait Passivation { this: Actor =>
  def passivationThreshold: FiniteDuration
  def shouldPassivate: Boolean

  final protected def schedulePassivation(): Unit =
    context.setReceiveTimeout(passivationThreshold)

  final protected def receivePassivationMessages: Receive = {
    case ReceiveTimeout ⇒
      if (shouldPassivate) {
        passivate()
      } else {
        schedulePassivation()
      }
    case Stop ⇒
      context.stop(self)
  }

  final protected def passivate(): Unit = {
    context.parent ! Passivate(stopMessage = Stop)
  }
}
