package io.aecor.distributedprocessing

import aecor.effect.{ Capture, CaptureFuture }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }
import cats.Functor
import cats.implicits._
import io.aecor.distributedprocessing.DistributedProcessing.RunningProcess

object StreamingProcess {
  final class Mk[F[_]] {
    def apply[A, SM, FM](source: Source[A, SM], flow: Flow[A, Unit, FM])(
      implicit mat: Materializer,
      F0: Functor[F],
      F1: CaptureFuture[F],
      F2: Capture[F]
    ): F[RunningProcess[F]] = Capture[F].capture {
      val (killSwitch, terminated) = source
        .viaMat(KillSwitches.single)(Keep.right)
        .via(flow)
        .toMat(Sink.ignore)(Keep.both)
        .run()
      RunningProcess(CaptureFuture[F].captureF(terminated).void, () => killSwitch.shutdown())
    }
  }
  def apply[F[_]]: Mk[F] = new Mk[F]
}
