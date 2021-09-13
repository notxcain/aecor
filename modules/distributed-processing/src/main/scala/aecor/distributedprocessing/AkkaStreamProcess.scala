package aecor.distributedprocessing

import aecor.distributedprocessing.DistributedProcessing._
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }
import cats.effect.Async
import cats.syntax.functor._

object AkkaStreamProcess {
  final class Builder[F[_]] {
    def apply[M](source: Source[Unit, M])(implicit F: Async[F],
                                          materializer: Materializer): Process[F] =
      Process(run = F.delay {
        val (killSwitch, terminated) = source
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(Sink.ignore)(Keep.both)
          .run()
        RunningProcess(
          Async[F].fromFuture(Async[F].delay(terminated)).void,
          F.delay(killSwitch.shutdown())
        )
      })
  }

  def apply[F[_]]: Builder[F] = new Builder[F]
}
