package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessing._
import aecor.effect.{ Capture, CaptureFuture }
import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }

object AkkaStreamProcess {
  final class Mk[F[_]] {
    def apply[A](
      source: Source[A, NotUsed],
      flow: Flow[A, Unit, NotUsed]
    )(implicit mat: Materializer, F0: CaptureFuture[F], F1: Capture[F]): Process[F] =
      Process(run = Capture[F].capture {
        val (killSwitch, terminated) = source
          .viaMat(KillSwitches.single)(Keep.right)
          .via(flow)
          .toMat(Sink.ignore)(Keep.both)
          .run()
        RunningProcess(
          CaptureFuture[F].captureFuture(terminated.map(_ => ())(mat.executionContext)),
          () => killSwitch.shutdown()
        )
      })
  }
  def apply[F[_]]: Mk[F] = new Mk[F]
}
