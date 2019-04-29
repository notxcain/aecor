package aecor.kafkadistributedprocessing

import aecor.util.effect._
import akka.NotUsed
import akka.kafka.ConsumerMessage.PartitionOffset
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ AutoSubscription, ConsumerSettings }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Source, Sink => AkkaSink }
import cats.effect.{ ConcurrentEffect, IO }
import cats.implicits._
import fs2.Stream
import fs2.interop.reactivestreams._
import org.apache.kafka.clients.consumer.ConsumerRecord

import scala.concurrent.Future

object interop {

  def sourceToStream[F[_], A, Mat](source: Source[A, Mat], materializer: Materializer)(
    implicit F: ConcurrentEffect[F]
  ): Stream[F, (Mat, Stream[F, A])] =
    Stream
      .eval(F.delay {
        source
          .toMat(AkkaSink.asPublisher(false))(Keep.both)
          .run()(materializer)
      })
      .map {
        case (mat, publisher) =>
          (mat, publisher.toStream[F]())
      }

  final case class TopicPartition[F[_], A](topic: String, partition: Int, messages: Stream[F, A])

  final case class CommittableMessage[F[_], K, V](record: ConsumerRecord[K, V],
                                                  committableOffset: CommittableOffset[F])

  final case class CommittableOffset[F[_]](commit: F[Unit], partitionOffset: PartitionOffset)

  def committablePartitionedStream[F[_], K, V](
    consumerSettings: ConsumerSettings[K, V],
    subscription: AutoSubscription,
    materializer: Materializer
  )(implicit F: ConcurrentEffect[F]): Stream[F, TopicPartition[F, CommittableMessage[F, K, V]]] =
    Consumer
      .committablePartitionedSource(consumerSettings, subscription)
      .toStream[F](materializer)
      .flatMap {
        case (control, stream) =>
          val shutdown =
            F.liftIO(
                IO.fromFuture(
                  IO(control.drainAndShutdown(Future.successful(()))(materializer.executionContext))
                )
              )
              .void
          stream.onFinalize(shutdown).map {
            case (tp, source) =>
              val messages = source.toStream[F](materializer).map { cm =>
                CommittableMessage(
                  cm.record,
                  CommittableOffset(
                    F.fromFuture(cm.committableOffset.commitScaladsl()).void,
                    cm.committableOffset.partitionOffset
                  )
                )
              }
              TopicPartition(tp.topic(), tp.partition(), messages)
          }
      }

  implicit final class ConsumerSettingsOps[K, V](val self: ConsumerSettings[K, V]) extends AnyVal {
    def committablePartitionedStream[F[_]: ConcurrentEffect](
      subscription: AutoSubscription,
      materializer: Materializer
    ): Stream[F, TopicPartition[F, CommittableMessage[F, K, V]]] =
      interop.committablePartitionedStream(self, subscription, materializer)
  }

  implicit final class SourceToStreamOps[A, Mat](val source: Source[A, Mat]) extends AnyVal {
    def toStream[F[_]](
      materializer: Materializer
    )(implicit F: ConcurrentEffect[F]): Stream[F, (Mat, Stream[F, A])] =
      sourceToStream(source, materializer)
  }

  implicit final class SourceWithNotUsedMatToStreamOps[A](val source: Source[A, NotUsed])
      extends AnyVal {
    def toStream[F[_]](materializer: Materializer)(implicit F: ConcurrentEffect[F]): Stream[F, A] =
      sourceToStream(source, materializer).flatMap(_._2)
  }

}
