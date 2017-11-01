package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessing._
import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import aecor.distributedprocessing.serialization.Message
import aecor.effect.Async
import aecor.effect.Async.ops._
import akka.actor.{ Actor, ActorLogging, Props, Status }
import akka.pattern._

private[aecor] object DistributedProcessingWorker {
  def props[F[_]: Async](processWithId: Int => Process[F]): Props =
    Props(new DistributedProcessingWorker[F](processWithId))

  final case class KeepRunning(workerId: Int) extends Message
}

private[aecor] final class DistributedProcessingWorker[F[_]: Async](processFor: Int => Process[F])
    extends Actor
    with ActorLogging {
  import context.dispatcher

  case class ProcessStarted(process: RunningProcess[F])
  case object ProcessTerminated

  var killSwitch: Option[() => Unit] = None

  override def postStop: Unit =
    killSwitch.foreach(_.apply())

  def receive: Receive = {
    case KeepRunning(workerId) =>
      log.info("[{}] Starting process", workerId)
      processFor(workerId).run.unsafeRun.map(ProcessStarted) pipeTo self
      context.become {
        case ProcessStarted(RunningProcess(watchTermination, terminate)) =>
          log.info("[{}] Process started", workerId)
          killSwitch = Some(terminate)
          watchTermination.unsafeRun.map(_ => ProcessTerminated) pipeTo self
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
