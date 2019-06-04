package aecor.kafkadistributedprocessing

import java.time.Duration
import java.util
import java.util.Properties
import java.util.concurrent.Executors

import aecor.data.Committable
import aecor.kafkadistributedprocessing.RebalanceEvents.RebalanceEvent
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{ Async, Concurrent, ConcurrentEffect, ContextShift, ExitCase, Resource, Timer }
import cats.implicits._
import cats.~>
import fs2.Stream
import org.apache.kafka.clients.consumer.{ Consumer, ConsumerRebalanceListener, KafkaConsumer }
import org.apache.kafka.common.serialization.{ Deserializer, Serializer }

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

private[kafkadistributedprocessing] object Kafka {

  type CompletionCallback[F[_]] = F[Unit]
  type Partition = Int

  final case class AssignedPartition[F[_]](partition: Partition,
                                           partitionCount: Int,
                                           watchRevocation: F[CompletionCallback[F]])

  final case class Channel[F[_]](watch: F[CompletionCallback[F]], call: F[Unit])

  def channel[F[_]: Concurrent]: F[Channel[F]] =
    for {
      deferredCallback <- Deferred[F, CompletionCallback[F]]
      watchCancelled <- Deferred[F, Unit]
      watch = deferredCallback.get
        .guaranteeCase {
          case ExitCase.Canceled =>
            println("Watch Canceled")
            watchCancelled.complete(())
          case x => println(s"Watch $x").pure[F]
        }
      call = watchCancelled.get
        .race(Deferred[F, Unit].flatMap { deferredCompletion =>
          deferredCallback.complete(deferredCompletion.complete(())) >> deferredCompletion.get
        })
        .void
    } yield Channel(watch, call)

  def runUntilCancelled[F[_]: Concurrent, A](
    watchCancellation: F[F[Unit]]
  )(fa: F[A]): F[Either[Unit, A]] =
    fa.race(watchCancellation).flatMap(_.sequence).map(_.swap)

  def createConsumerAccess[F[_]: ContextShift](
    config: Properties
  )(implicit F: Async[F]): Resource[F, ConsumerAccess[F, Unit, Unit]] =
    Resource.make(F.delay {
      val original = Thread.currentThread.getContextClassLoader
      Thread.currentThread.setContextClassLoader(null)
      val consumer = new KafkaConsumer[Unit, Unit](config, new UnitSerde, new UnitSerde)
      Thread.currentThread.setContextClassLoader(original)
      new ConsumerAccess[F, Unit, Unit](consumer)
    })(useConsumer => useConsumer(_.close()))

  def watchRebalanceEvents[F[_]: ConcurrentEffect](
    accessConsumer: ConsumerAccess[F, Unit, Unit],
    topic: String
  )(implicit timer: Timer[F]): Stream[F, Committable[F, RebalanceEvent]] = {

    def subscribe(listener: ConsumerRebalanceListener) =
      accessConsumer(_.subscribe(List(topic).asJava, listener))
    val unsubscribe =
      accessConsumer(_.unsubscribe())

    val poll = accessConsumer(_.poll(Duration.ofMillis(50))).void

    Stream
      .force(
        RebalanceEvents[F]
          .subscribe(subscribe)
          .map(
            _.onFinalize(unsubscribe)
              .concurrently(Stream.repeatEval(poll >> timer.sleep(500.millis)))
          )
      )

  }

  def assignPartitions[F[_]: ConcurrentEffect: Timer: ContextShift](
    config: Properties,
    topic: String
  ): Stream[F, AssignedPartition[F]] =
    Stream
      .resource(createConsumerAccess[F](config))
      .flatMap { accessConsumer =>
        val fetchPartitionCount = accessConsumer(_.partitionsFor(topic).size())
        Stream
          .eval(fetchPartitionCount)
          .flatMap { partitionCount =>
            watchRebalanceEvents(accessConsumer, topic)
              .evalScan((List.empty[AssignedPartition[F]], Map.empty[Partition, F[Unit]])) {
                case ((_, revokeTokens), Committable(commit, event)) =>
                  val handleEvent = event match {
                    case RebalanceEvent.PartitionsAssigned(partitions) =>
                      partitions.toList
                        .traverse { partition =>
                          channel[F].map {
                            case Channel(watch, call) =>
                              AssignedPartition(partition, partitionCount, watch) -> call
                          }
                        }
                        .map { list =>
                          val assignedPartitions = list.map(_._1)
                          val updatedRevocationCallbacks = revokeTokens ++ list.map {
                            case (AssignedPartition(partition, _, _), revoke) =>
                              partition -> revoke
                          }
                          (assignedPartitions, updatedRevocationCallbacks)
                        }
                    case RebalanceEvent.PartitionsRevoked(partitions) =>
                      partitions.toList
                        .flatMap(revokeTokens.get)
                        .sequence_
                        .as((List.empty[AssignedPartition[F]], revokeTokens -- partitions))
                  }
                  handleEvent <* commit
              }
          }
          .flatMap {
            case (assignedPartitions, _) =>
              Stream.emits(assignedPartitions)
          }
      }

  class UnitSerde extends Serializer[Unit] with Deserializer[Unit] {
    private val emptyByteArray = Array.empty[Byte]
    override def serialize(topic: String, data: Unit): Array[Byte] = emptyByteArray
    override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
    override def deserialize(topic: String, data: Array[Byte]): Unit = ()
    override def close(): Unit = ()
  }

  final class ConsumerAccess[F[_], K, V](consumer: Consumer[K, V])(implicit F: Async[F],
                                                                   contextShift: ContextShift[F])
      extends ((Consumer[K, V] => ?) ~> F) {
    private val executor = Executors.newSingleThreadExecutor()
    override def apply[A](f: Consumer[K, V] => A): F[A] =
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
  }

}
