package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessing._
import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import aecor.distributedprocessing.serialization.Message
import cats.effect.syntax.effect._
import akka.actor.{ Actor, ActorLogging, Props, Status }
import akka.pattern._
import cats.effect.Effect
import cats.implicits._
private[aecor] object DistributedProcessingWorker {
  def props[F[_]: Effect](processWithId: Int => Process[F]): Props =
    Props(new DistributedProcessingWorker[F](processWithId))

  final case class KeepRunning(workerId: Int) extends Message
}

private[aecor] final class DistributedProcessingWorker[F[_]: Effect](processFor: Int => Process[F])
    extends Actor
    with ActorLogging {
  import context.dispatcher

  case class ProcessStarted(process: RunningProcess[F])
  case object ProcessTerminated

  var killSwitch: Option[F[Unit]] = None

  override def postStop: Unit =
    killSwitch.foreach(_.toIO.unsafeRunSync())

  def receive: Receive = {
    case KeepRunning(workerId) =>
      log.info("[{}] Starting process", workerId)
      processFor(workerId).run
        .map(ProcessStarted)
        .toIO
        .unsafeToFuture() pipeTo self
      context.become {
        case ProcessStarted(RunningProcess(watchTermination, terminate)) =>
          log.info("[{}] Process started", workerId)
          killSwitch = Some(terminate)
          watchTermination.toIO.map(_ => ProcessTerminated).unsafeToFuture() pipeTo self
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
