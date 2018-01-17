package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessing._
import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }
import cats.Eval
import cats.effect.{ Async, IO }
import cats.implicits._

object AkkaStreamProcess {
  final class Mk[F[_]] {
    def apply[A](
      source: Source[A, NotUsed],
      flow: Flow[A, Unit, NotUsed]
    )(implicit mat: Materializer, F: Async[F]): Process[F] =
      Process(run = F.delay {
        val (killSwitch, terminated) = source
          .viaMat(KillSwitches.single)(Keep.right)
          .via(flow)
          .toMat(Sink.ignore)(Keep.both)
          .run()
        RunningProcess(
          F.liftIO(IO.fromFuture(Eval.now(terminated))(mat.executionContext).void),
          () => killSwitch.shutdown()
        )
      })
  }
  def apply[F[_]]: Mk[F] = new Mk[F]
}
