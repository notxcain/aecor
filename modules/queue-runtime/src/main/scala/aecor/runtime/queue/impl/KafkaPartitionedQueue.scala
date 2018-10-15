package aecor.runtime.queue.impl

import java.util
import java.util.UUID

import aecor.runtime.queue.PartitionedQueue
import aecor.runtime.queue.PartitionedQueue.Instance
import aecor.runtime.queue.impl.KafkaPartitionedQueue.Serialization
import aecor.util.effect._
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ ConsumerSettings, Subscriptions }
import akka.stream.{ ActorMaterializer, Materializer }
import cats.effect._
import cats.effect.concurrent.Deferred
import cats.implicits._
import fs2.concurrent.{ Enqueue, Queue }
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.serialization.{ Deserializer, Serializer }
import scodec.Codec
import scodec.bits.BitVector
import streamz.converter._

import scala.concurrent.duration._

class KafkaPartitionedQueue[F[_], K, A](
  system: ActorSystem,
  contactPoints: Set[String],
  topicName: String,
  groupId: String,
  extractKey: A => K,
  serialization: Serialization[K, A],
  producerProperties: Map[String, String],
  consumerProperties: Map[String, String],
  commitGrouping: (Int, FiniteDuration)
)(implicit F: ConcurrentEffect[F], contextShift: ContextShift[F], timer: Timer[F])
    extends PartitionedQueue[F, A] {
  override def start: Resource[F, Instance[F, A]] =
    (startProducer, startConsumer).mapN(Instance(_, _))

  def startConsumer: Resource[F, fs2.Stream[F, fs2.Stream[F, A]]] =
    Resource[F, fs2.Stream[F, fs2.Stream[F, A]]] {
      Deferred[F, Consumer.Control].flatMap { deferredControl =>
        Deferred[F, Unit].map { deferredComplete =>
          implicit val materializer: Materializer = ActorMaterializer()(system)

          val consumerSettings =
            ConsumerSettings(system, serialization.keyDeserializer, serialization.valueDeserializer)
              .withBootstrapServers(contactPoints.mkString(","))
              .withClientId(UUID.randomUUID().toString)
              .withGroupId(groupId)
              .withProperties(consumerProperties)

          val stream = Consumer
            .committablePartitionedSource(consumerSettings, Subscriptions.topics(topicName))
            .toStream[F](
              control => F.runAsync(deferredControl.complete(control))(_ => IO.unit).unsafeRunSync()
            )
            .evalMap {
              case (i, partition) =>
                println(s"Assigned partition $i")
                Queue.unbounded[F, CommittableOffset].map { commits =>
                  partition
                    .toStream()
                    .evalMap { m =>
                      commits
                        .enqueue1(m.committableOffset)
                        .as(m.record.value())
                    }
                    .concurrently(
                      commits.dequeue
                        .groupWithin(commitGrouping._1, commitGrouping._2)
                        .evalMap(_.last.traverse_(o => F.fromFuture(o.commitScaladsl())))
                    )
                    .onFinalize(F.delay(println(s"Partition revoked $i")))
                }
            }
            .onFinalize(deferredComplete.complete(()))

          val free =
            deferredControl.get.flatMap { c =>
              F.fromFuture(c.stop()) >> deferredComplete.get >> F.fromFuture(c.shutdown()).void
            }
          (stream, free)
        }
      }
    }

  def startProducer: Resource[F, Enqueue[F, A]] = Resource[F, Enqueue[F, A]] {
    F.suspend {
      val defaults =
        Map(
          ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> contactPoints
            .mkString(","),
          ProducerConfig.ACKS_CONFIG -> "0",
          ProducerConfig.BATCH_SIZE_CONFIG -> "32000",
          ProducerConfig.LINGER_MS_CONFIG -> "10",
          ProducerConfig.COMPRESSION_TYPE_CONFIG -> "snappy"
        )

      val javaProps =
        (defaults ++ producerProperties).foldLeft(new java.util.Properties) {
          case (p, (k, v)) => p.put(k, v); p
        }
      F.catchNonFatal(
          new KafkaProducer(javaProps, serialization.keySerializer, serialization.valueSerializer)
        )
        .map { underlying =>
          val out = new Enqueue[F, A] {
            override def enqueue1(a: A): F[Unit] =
              F.async[Unit] { callback =>
                underlying.send(
                  new ProducerRecord(topicName, extractKey(a), a),
                  new Callback {
                    override def onCompletion(metadata: RecordMetadata,
                                              exception: Exception): Unit =
                      if (exception eq null) {
                        callback(Right(()))
                      } else {
                        callback(Left(exception))
                      }
                  }
                )
                ()
              }
            override def offer1(a: A): F[Boolean] = enqueue1(a).as(true)
          }
          (out, F.delay(underlying.close()))
        }
    }
  }
}

object KafkaPartitionedQueue {
  final case class Serialization[K, A](keySerializer: Serializer[K],
                                       keyDeserializer: Deserializer[K],
                                       valueSerializer: Serializer[A],
                                       valueDeserializer: Deserializer[A])
  object Serialization {
    def scodec[K, A](implicit K: Codec[K], A: Codec[A]): Serialization[K, A] =
      Serialization(
        new Serializer[K] {
          override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
          override def serialize(topic: String, data: K): Array[Byte] =
            K.encode(data)
              .fold(e => throw new IllegalArgumentException(e.messageWithContext), _.toByteArray)
          override def close(): Unit = ()
        },
        new Deserializer[K] {
          override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
          override def deserialize(topic: String, data: Array[Byte]): K =
            K.decodeValue(BitVector(data))
              .fold(e => throw new IllegalArgumentException(e.messageWithContext), identity)
          override def close(): Unit = ()
        },
        new Serializer[A] {
          override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
          override def serialize(topic: String, data: A): Array[Byte] =
            A.encode(data)
              .fold(e => throw new IllegalArgumentException(e.messageWithContext), _.toByteArray)
          override def close(): Unit = ()
        },
        new Deserializer[A] {
          override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
          override def deserialize(topic: String, data: Array[Byte]): A =
            A.decodeValue(BitVector(data))
              .fold(e => throw new IllegalArgumentException(e.messageWithContext), identity)
          override def close(): Unit = ()
        }
      )
  }
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer, K, A](
    system: ActorSystem,
    contactPoints: Set[String],
    topicName: String,
    groupId: String,
    extractKey: A => K,
    serialization: Serialization[K, A],
    producerProperties: Map[String, String] = Map.empty,
    consumerProperties: Map[String, String] = Map.empty,
    commitGrouping: (Int, FiniteDuration) = (100, 1.second)
  ): PartitionedQueue[F, A] =
    new KafkaPartitionedQueue(
      system,
      contactPoints,
      topicName,
      groupId,
      extractKey,
      serialization,
      producerProperties,
      consumerProperties,
      commitGrouping
    )

}
