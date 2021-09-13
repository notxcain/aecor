package aecor.example.common

import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.interop.reactivestreams._
import fs2.Stream

object Fs2AkkaStreamInterop {
  implicit final class SourceToStream[A, Mat](val self: Source[A, Mat]) extends AnyVal {
    def materializeToStream[F[_]](
        materializer: Materializer
    )(implicit F: Async[F]): F[(Mat, Stream[F, A])] = F.delay {
      val (mat, publisher) = self.toMat(Sink.asPublisher(false))(Keep.both).run()(materializer)
      (mat, publisher.toStream[F])
    }
    def toStream[F[_]](materializer: Materializer)(implicit F: Async[F]): Stream[F, A] =
      Stream.force(materializeToStream[F](materializer).map(_._2))
  }
}
