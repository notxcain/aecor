package aecor.kafkadistributedprocessing.internal

import java.time.Duration
import java.util.Properties
import java.util.concurrent.Executors

import cats.effect.{ Async, ContextShift, Resource }
import cats.~>
import org.apache.kafka.clients.consumer.{ Consumer, ConsumerRebalanceListener, ConsumerRecords }
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.serialization.Deserializer

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

private[kafkadistributedprocessing] final class KafkaConsumer[F[_], K, V](
  withConsumer: (Consumer[K, V] => ?) ~> F
) {

  def subscribe(topics: Set[String], listener: ConsumerRebalanceListener): F[Unit] =
    withConsumer(_.subscribe(topics.asJava, listener))

  def subscribe(topics: Set[String]): F[Unit] =
    withConsumer(_.subscribe(topics.asJava))

  val unsubscribe: F[Unit] =
    withConsumer(_.unsubscribe())

  def partitionsFor(topic: String): F[Set[PartitionInfo]] =
    withConsumer(_.partitionsFor(topic).asScala.toSet)

  def close: F[Unit] =
    withConsumer(_.close())

  def poll(timeout: FiniteDuration): F[ConsumerRecords[K, V]] =
    withConsumer(_.poll(Duration.ofNanos(timeout.toNanos)))
}

private[kafkadistributedprocessing] object KafkaConsumer {
  final class Create[F[_]] {
    def apply[K, V](
      config: Properties,
      keyDeserializer: Deserializer[K],
      valueDeserializer: Deserializer[V]
    )(implicit F: Async[F], contextShift: ContextShift[F]): Resource[F, KafkaConsumer[F, K, V]] = {
      val create = F.suspend {

        val executor = Executors.newSingleThreadExecutor()

        def eval[A](a: => A): F[A] =
          contextShift.evalOn(ExecutionContext.fromExecutor(executor)) {
            F.async[A] { cb =>
              executor.execute(new Runnable {
                override def run(): Unit =
                  cb {
                    try Right(a)
                    catch {
                      case e: Throwable => Left(e)
                    }
                  }
              })
            }
          }

        eval {
          val original = Thread.currentThread.getContextClassLoader
          Thread.currentThread.setContextClassLoader(null)
          val consumer = new org.apache.kafka.clients.consumer.KafkaConsumer[K, V](
            config,
            keyDeserializer,
            valueDeserializer
          )
          Thread.currentThread.setContextClassLoader(original)
          val withConsumer = new ((Consumer[K, V] => ?) ~> F) {
            def apply[A](f: Consumer[K, V] => A): F[A] =
              eval(f(consumer))
          }
          new KafkaConsumer[F, K, V](withConsumer)
        }
      }
      Resource.make(create)(_.close)
    }
  }
  def create[F[_]]: Create[F] = new Create[F]
}
