package aecor.streaming

import akka.actor.{ Actor, ActorLogging, Props, Status }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }

private[aecor] object StreamSupervisorActor {
  def props[A, SM, FM](source: Source[A, SM],
                       flow: Flow[A, Unit, FM])(implicit mat: Materializer): Props =
    Props(classOf[StreamSupervisorActor[A, SM, FM]], source, flow, mat)

  case object Shutdown
}

private[aecor] class StreamSupervisorActor[A, SM, FM](
  source: Source[A, SM],
  flow: Flow[A, Unit, FM]
)(implicit mat: Materializer)
    extends Actor
    with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  val (killSwitch, streamUnit) =
    source
      .viaMat(KillSwitches.single)(Keep.right)
      .via(flow)
      .toMat(Sink.ignore)(Keep.both)
      .run()

  streamUnit pipeTo self

  override def postStop: Unit =
    killSwitch.shutdown()

  def receive: Receive = {
    case Status.Failure(e) =>
      log.error(e, "Stream failed")
      throw e

    case StreamSupervisorActor.Shutdown =>
      context stop self
      context become Actor.ignoringBehavior

    case Unit =>
      throw new IllegalStateException("Stream terminated when it shouldn't")

  }
}
