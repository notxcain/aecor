package aecor.kafkadistributedprocessing

import java.time.Duration
import java.util
import java.util.Properties
import java.util.concurrent.Executors

import aecor.data.Committable
import aecor.kafkadistributedprocessing.RebalanceEvents.RebalanceEvent
import cats.effect.concurrent.{ Deferred, Ref }
import cats.effect.implicits._
import cats.effect.{ ConcurrentEffect, ContextShift, ExitCase, Timer }
import cats.implicits._
import cats.~>
import fs2.Stream
import org.apache.kafka.clients.consumer.{ Consumer, ConsumerRebalanceListener, KafkaConsumer }
import org.apache.kafka.common.serialization.{ Deserializer, Serializer }

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

private[kafkadistributedprocessing] object Kafka {

  type RevocationCallback[F[_]] = F[Unit]
  type RevocationObserver[F[_]] = RevocationCallback[F] => F[Unit]
  type Partition = Int

  final case class AssignedPartition[F[_]](partition: Partition,
                                           partitionCount: Int,
                                           watchRevocation: F[RevocationCallback[F]])

  def assignPartitions[F[_]](config: Properties, topic: String)(
    implicit F: ConcurrentEffect[F],
    timer: Timer[F],
    contextShift: ContextShift[F]
  ): Stream[F, AssignedPartition[F]] =
    Stream
      .bracket(F.delay {
        val consumer =
          new KafkaConsumer(config, UnitSerde, UnitSerde)
        val executor = Executors.newSingleThreadExecutor()
        new ((Consumer[Unit, Unit] => ?) ~> F) {
          override def apply[A](f: Consumer[Unit, Unit] => A): F[A] =
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
      })(useConsumer => useConsumer(_.close()))
      .flatMap { accessConsumer =>
        val fetchPartitionCount = accessConsumer(_.partitionsFor(topic).size())

        def subscribe(listener: ConsumerRebalanceListener) =
          accessConsumer(_.subscribe(List(topic).asJava, listener))

        val poll = accessConsumer(_.poll(Duration.ofMillis(50))).void

        Stream
          .eval(
            RebalanceEvents[F]
              .subscribe(
                listener =>
                  subscribe(listener) >>
                  fetchPartitionCount
              )
          )
          .flatMap {
            case (pc, events) =>
              events
                .concurrently(Stream.repeatEval(poll >> timer.sleep(500.millis)))
                .evalScan(
                  (List.empty[AssignedPartition[F]], Map.empty[Partition, RevocationObserver[F]])
                ) {
                  case ((_, revocationCallbacks), Committable(commit, event)) =>
                    event match {
                      case RebalanceEvent.PartitionsRevoked(partitions) =>
                        partitions.toList
                          .traverse_ { partition =>
                            revocationCallbacks.get(partition).traverse { callback =>
                              Deferred[F, Unit].flatMap { revocationCompletion =>
                                callback(revocationCompletion.complete(())) >> revocationCompletion.get
                              }
                            }
                          } >> commit.as((List.empty, revocationCallbacks -- partitions))
                      case RebalanceEvent.PartitionsAssigned(partitions) =>
                        partitions.toList
                          .traverse { p =>
                            Deferred[F, F[Unit]].flatMap { x =>
                              Ref[F].of(false).map { cancelled =>
                                val assignedPartition =
                                  AssignedPartition(p, pc, x.get.guaranteeCase {
                                    case ExitCase.Canceled => cancelled.set(true)
                                    case _                 => ().pure[F]
                                  })
                                val completionCallback = (complete: F[Unit]) =>
                                  cancelled.get.ifM(complete, x.complete(complete))
                                (assignedPartition, completionCallback)
                              }
                            }
                          }
                          .flatMap { list =>
                            val assignedPartitions = list.map(_._1)
                            val updatedRevocationCallbacks = revocationCallbacks ++ list.map {
                              case (AssignedPartition(partition, _, _), callback) =>
                                partition -> callback
                            }
                            commit.as((assignedPartitions, updatedRevocationCallbacks))
                          }
                    }
                }
          }
          .flatMap {
            case (assignedPartitions, _) =>
              Stream.emits(assignedPartitions)
          }
      }

  private object UnitSerde extends Serializer[Unit] with Deserializer[Unit] {
    private val emptyByteArray = Array.empty[Byte]
    override def serialize(topic: String, data: Unit): Array[Byte] = emptyByteArray
    override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
    override def deserialize(topic: String, data: Array[Byte]): Unit = ()
    override def close(): Unit = ()
  }
}
