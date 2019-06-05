package aecor.kafkadistributedprocessing.internal

import java.time.Duration
import java.util.Properties
import java.util.concurrent.Executors

import aecor.kafkadistributedprocessing.internal.Kafka.UnitDeserializer
import cats.effect.{ Async, ContextShift, Resource }
import org.apache.kafka.clients.consumer.{
  Consumer,
  ConsumerRebalanceListener,
  ConsumerRecord,
  ConsumerRecords
}
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.serialization.Deserializer

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

private[kafkadistributedprocessing] final class KafkaConsumer[F[_], K, V](consumer: Consumer[K, V])(
  implicit F: Async[F],
  contextShift: ContextShift[F]
) {
  private val executor = Executors.newSingleThreadExecutor()
  private def apply[A](f: Consumer[K, V] => A): F[A] =
    contextShift.evalOn(ExecutionContext.fromExecutor(executor)) {
      F.async[A] { cb =>
        executor.execute(new Runnable {
          override def run(): Unit =
            cb {
              try Right(f(consumer))
              catch {
                case e: Throwable => Left(e)
              }
            }
        })
      }
    }

  def subscribe(topics: Set[String], listener: ConsumerRebalanceListener): F[Unit] =
    this(_.subscribe(topics.asJava, listener))

  def subscribe(topics: Set[String]): F[Unit] =
    this(_.subscribe(topics.asJava))

  val unsubscribe: F[Unit] =
    this(_.unsubscribe())

  def partitionsFor(topic: String): F[Set[PartitionInfo]] =
    this(_.partitionsFor(topic).asScala.toSet)

  def close: F[Unit] = this(_.close())

  def poll(timeout: FiniteDuration): F[ConsumerRecords[K, V]] =
    this(_.poll(Duration.ofNanos(timeout.toNanos)))
}

private[kafkadistributedprocessing] object KafkaConsumer {
  final class Create[F[_]] {
    def apply[K, V](
      config: Properties,
      keyDeserializer: Deserializer[K],
      valueDeserializer: Deserializer[V]
    )(implicit F: Async[F], contextShift: ContextShift[F]): Resource[F, KafkaConsumer[F, K, V]] = {
      val create = F.delay {
        val original = Thread.currentThread.getContextClassLoader
        Thread.currentThread.setContextClassLoader(null)
        val consumer = new org.apache.kafka.clients.consumer.KafkaConsumer[K, V](
          config,
          keyDeserializer,
          valueDeserializer
        )
        Thread.currentThread.setContextClassLoader(original)
        new KafkaConsumer[F, K, V](consumer)
      }
      Resource.make(create)(_.close)
    }
  }
  def create[F[_]]: Create[F] = new Create[F]
}
