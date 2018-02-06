package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessing._
import aecor.util.effect._

import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }
import cats.effect.Async
import cats.implicits._

object AkkaStreamProcess {
  final class Builder[F[_]] {
    def apply[A, SourceMat, FlowMat](
      source: Source[A, SourceMat],
      flow: Flow[A, Unit, FlowMat]
    )(implicit F: Async[F], materializer: Materializer): Process[F] =
      Process(run = F.delay {
        import materializer.executionContext
        val (killSwitch, terminated) = source
          .viaMat(KillSwitches.single)(Keep.right)
          .via(flow)
          .toMat(Sink.ignore)(Keep.both)
          .run()
        RunningProcess(F.fromFuture(terminated).void, F.delay(killSwitch.shutdown()))
      })
  }
  def apply[F[_]]: Builder[F] = new Builder[F]
}
