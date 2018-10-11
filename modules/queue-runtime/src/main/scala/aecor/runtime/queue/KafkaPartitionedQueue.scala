package aecor.runtime.queue
import java.util.UUID

import aecor.runtime.queue.KafkaPartitionedQueue.Serialization
import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.{ ConsumerSettings, Subscriptions }
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import cats.effect.concurrent.Deferred
import cats.effect._
import fs2.concurrent.{ Enqueue, Queue }
import org.apache.kafka.common.serialization.{ Deserializer, Serializer }
import cats.implicits._
import streamz.converter._
import fs2.Stream
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.TopicPartition

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
  override def start: Resource[F, (Enqueue[F, A], fs2.Stream[F, fs2.Stream[F, A]])] =
    (startProducer, startConsumer).tupled

  def startConsumer: Resource[F, fs2.Stream[F, fs2.Stream[F, A]]] =
    Resource[F, fs2.Stream[F, fs2.Stream[F, A]]] {
      Deferred[F, Consumer.Control].map { deferred =>
        implicit val materializer = ActorMaterializer()(system)
        val consumerSettings =
          ConsumerSettings(system, serialization.keyDeserializer, serialization.valueDeserializer)
            .withBootstrapServers(contactPoints.mkString(","))
            .withClientId(UUID.randomUUID().toString)
            .withGroupId(groupId)
            .withProperties(consumerProperties)

        val x: Stream[F, (TopicPartition, Source[CommittableMessage[K, A], NotUsed])] = Consumer
          .committablePartitionedSource(consumerSettings, Subscriptions.topics(topicName))
          .toStream[F](
            control => F.runAsync(deferred.complete(control))(_ => IO.unit).unsafeRunSync()
          )

        val out = x.evalMap {
          case (_, partition) =>
            Queue.unbounded[F, F[Unit]].map { commits =>
              partition
                .toStream[F]()
                .mapAsync(10) { c =>
                  commits
                    .enqueue1(
                      F.liftIO(IO.fromFuture(IO(c.committableOffset.commitScaladsl()))).void
                    )
                    .as(c.record.value())
                }
                .concurrently(
                  commits.dequeue
                    .groupWithin(commitGrouping._1, commitGrouping._2)
                    .evalMap(_.last.sequence_)
                )
            }
        }
        (out, deferred.get.flatMap(c => F.liftIO(IO.fromFuture(IO(c.shutdown())).void)))
      }
    }

  def startProducer: Resource[F, Enqueue[F, A]] = Resource[F, Enqueue[F, A]] {
    F.suspend {
      val defaults =
        Map(
          ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> contactPoints
            .mkString(","),
          ProducerConfig.ACKS_CONFIG -> "all"
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
