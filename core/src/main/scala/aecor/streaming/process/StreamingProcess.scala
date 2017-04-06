package aecor.streaming.process

import aecor.effect.{ Capture, CaptureFuture }
import aecor.streaming.process.DistributedProcessing.RunningProcess
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }
import cats.Functor
import cats.implicits._

object StreamingProcess {
  def apply[F[_]: Functor: CaptureFuture: Capture, A, SM, FM](
    source: Source[A, SM],
    flow: Flow[A, Unit, FM]
  )(implicit mat: Materializer): F[RunningProcess[F]] = Capture[F].capture {
    val (killSwitch, terminated) = source
      .viaMat(KillSwitches.single)(Keep.right)
      .via(flow)
      .toMat(Sink.ignore)(Keep.both)
      .run()
    RunningProcess(CaptureFuture[F].captureF(terminated).void, () => killSwitch.shutdown())
  }
}
