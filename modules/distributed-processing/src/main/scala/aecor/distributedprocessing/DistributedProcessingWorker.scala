package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessing._
import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import aecor.distributedprocessing.serialization.Message
import aecor.util.effect._
import akka.actor.{ Actor, ActorLogging, Props, Status }
import akka.pattern._
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime
import cats.implicits._

import scala.concurrent.ExecutionContext

private[aecor] object DistributedProcessingWorker {
  def props[F[_]: Async](processWithId: Int => Process[F], processName: String): F[Props] =
    Dispatcher[F].allocated
      .map(_._1)
      .map(
        dispatcher =>
          Props(new DistributedProcessingWorker[F](processWithId, processName, dispatcher))
      )

  final case class KeepRunning(workerId: Int) extends Message
}

private[aecor] final class DistributedProcessingWorker[F[_]: Async](processFor: Int => Process[F],
                                                                    processName: String,
                                                                    dispatcher: Dispatcher[F])
    extends Actor
    with ActorLogging {

  private implicit val ioRuntime: IORuntime = IORuntime.global
  private implicit val executionContext: ExecutionContext = ioRuntime.compute

  case class ProcessStarted(process: RunningProcess[F])
  case object ProcessTerminated

  var killSwitch: Option[F[Unit]] = None

  override def postStop: Unit =
    killSwitch.foreach(dispatcher.unsafeRunSync)

  def receive: Receive = {
    case KeepRunning(workerId) =>
      log.info("[{}] Starting process {}", workerId, processName)

      processFor(workerId).run
        .map(ProcessStarted)
        .unsafeToIO(dispatcher)
        .unsafeToFuture() pipeTo self

      context.become {
        case ProcessStarted(RunningProcess(watchTermination, terminate)) =>
          log.info("[{}] Process started {}", workerId, processName)
          killSwitch = Some(terminate)
          watchTermination.unsafeToIO(dispatcher).as(ProcessTerminated).unsafeToFuture() pipeTo self
          context.become {
            case Status.Failure(e) =>
              log.error(e, "Process failed {}", processName)
              throw e
            case ProcessTerminated =>
              log.error("Process terminated {}", processName)
              throw new IllegalStateException(s"Process terminated $processName")
          }
        case Status.Failure(e) =>
          log.error(e, "Process failed to start {}", processName)
          throw e
        case KeepRunning(_) => ()
      }
  }
}
