package aecor.streaming.process

import aecor.effect.Async
import Async.ops._
import aecor.streaming.process.DistributedProcessingWorker._
import akka.actor.{ Actor, ActorLogging, Props, Status }
import akka.pattern._
import cats.Functor
import cats.implicits._
import DistributedProcessing._

private[aecor] object DistributedProcessingWorker {
  def props[F[_]: Async: Functor](processWithId: Int => F[RunningProcess[F]]): Props =
    Props(new DistributedProcessingWorker[F](processWithId))

  final case class KeepRunning(workerId: Int)
}

private[aecor] class DistributedProcessingWorker[F[_]: Async: Functor](
  processFor: Int => F[RunningProcess[F]]
) extends Actor
    with ActorLogging {
  import context.dispatcher

  case class ProcessStarted(process: RunningProcess[F])
  case object ProcessTerminated

  var killSwitch: Option[() => Unit] = None

  override def postStop: Unit =
    killSwitch.foreach(_.apply())

  def receive: Receive = {
    case KeepRunning(workerId) =>
      processFor(workerId).map(ProcessStarted).unsafeRun pipeTo self
      context.become {
        case ProcessStarted(RunningProcess(watchTermination, terminate)) =>
          killSwitch = Some(terminate)
          watchTermination.map(_ => ProcessTerminated).unsafeRun pipeTo self
          context.become {
            case Status.Failure(e) =>
              log.error(e, "Process failed")
              throw e

            case ProcessTerminated =>
              throw new IllegalStateException("Process terminated")
          }
        case Status.Failure(e) =>
          log.error(e, "Process failed to start")
          throw e
        case KeepRunning(_) =>
      }
  }
}
