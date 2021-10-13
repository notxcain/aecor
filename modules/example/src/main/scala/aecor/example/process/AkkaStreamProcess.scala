package aecor.example.process

import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.{ KillSwitches, Materializer }
import cats.effect.Async
import cats.syntax.all._

object AkkaStreamProcess {
  final class Builder[F[_]] {
    def apply[M](source: Source[Unit, M], materializer: Materializer)(implicit
        F: Async[F]
    ): F[Unit] =
      F.bracket(
        F.delay(
          source
            .viaMat(KillSwitches.single)(Keep.right)
            .toMat(Sink.ignore)(Keep.both)
            .run()(materializer)
        )
      )(x => F.fromFuture(F.delay(x._2)).void)(x => F.delay(x._1.shutdown()))

  }
  def apply[F[_]]: Builder[F] = new Builder[F]
}
