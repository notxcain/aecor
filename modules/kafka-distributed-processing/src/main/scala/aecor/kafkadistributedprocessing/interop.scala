package aecor.kafkadistributedprocessing

import java.time.Duration
import java.util
import java.util.Properties
import java.util.concurrent.Executors

import aecor.kafkadistributedprocessing.interop.EnqueueingRebalanceListener.RebalanceCommand
import cats.effect.concurrent.{ Deferred, Ref }
import cats.effect.implicits._
import cats.effect.{ ConcurrentEffect, ContextShift, ExitCase, Timer }
import cats.implicits._
import cats.~>
import fs2.Stream
import fs2.concurrent.Queue
import org.apache.kafka.clients.consumer.{ Consumer, ConsumerRebalanceListener, KafkaConsumer }
import org.apache.kafka.common
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object interop {

  final case class AssignedPartition[F[_]](partition: Int,
                                           partitionCount: Int,
                                           watchRevocation: F[F[Unit]])

  final class EnqueueingRebalanceListener[F[_]](enqueue: RebalanceCommand[F] => F[Unit])(
    implicit F: ConcurrentEffect[F]
  ) extends ConsumerRebalanceListener {

    private def enqueueWithCompletionToken[A](f: F[Unit] => RebalanceCommand[F]): F[Unit] =
      Deferred[F, Unit].flatMap(completion => enqueue(f(completion.complete(()))) >> completion.get)

    override def onPartitionsRevoked(partitions: util.Collection[common.TopicPartition]): Unit =
      F.toIO(
          enqueueWithCompletionToken(
            RebalanceCommand.RevokePartitions(partitions.asScala.map(_.partition()).toSet, _)
          )
        )
        .unsafeRunSync()

    override def onPartitionsAssigned(partitions: util.Collection[common.TopicPartition]): Unit =
      F.toIO(
          enqueueWithCompletionToken(
            RebalanceCommand.AssignPartitions(partitions.asScala.map(_.partition()).toSet, _)
          )
        )
        .unsafeRunSync()
  }

  object EnqueueingRebalanceListener {
    final class UsePartiallyApplied[F[_]] {
      def use[A](
        subscribe: ConsumerRebalanceListener => F[A]
      )(implicit F: ConcurrentEffect[F]): F[(A, Stream[F, RebalanceCommand[F]])] =
        for {
          queue <- Queue.unbounded[F, RebalanceCommand[F]]
          listener = new EnqueueingRebalanceListener[F](queue.enqueue1)
          a <- subscribe(listener)
        } yield (a, queue.dequeue)
    }

    def apply[F[_]]: UsePartiallyApplied[F] = new UsePartiallyApplied[F]

    sealed abstract class RebalanceCommand[F[_]]
    object RebalanceCommand {
      final case class RevokePartitions[F[_]](partitions: Set[Int], commit: F[Unit])
          extends RebalanceCommand[F]
      final case class AssignPartitions[F[_]](partitions: Set[Int], commit: F[Unit])
          extends RebalanceCommand[F]
    }
  }

  def assignPartitions[F[_]](config: Properties, topic: String)(
    implicit F: ConcurrentEffect[F],
    timer: Timer[F],
    contextShift: ContextShift[F]
  ): Stream[F, AssignedPartition[F]] =
    Stream
      .bracket(F.delay {
        val consumer =
          new KafkaConsumer(config, new ByteArrayDeserializer, new ByteArrayDeserializer)
        val executor = Executors.newSingleThreadExecutor()
        new ((Consumer[Array[Byte], Array[Byte]] => ?) ~> F) {
          override def apply[A](f: Consumer[Array[Byte], Array[Byte]] => A): F[A] =
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
            EnqueueingRebalanceListener[F]
              .use(
                listener =>
                  subscribe(listener) >>
                  fetchPartitionCount
              )
          )
          .flatMap {
            case (pc, partitions) =>
              partitions
                .concurrently(Stream.repeatEval(poll >> timer.sleep(500.millis)))
                .evalScan((List.empty[AssignedPartition[F]], Map.empty[Int, F[Unit] => F[Unit]])) {
                  case ((_, revocationCallbacks), command) =>
                    command match {
                      case RebalanceCommand.RevokePartitions(partitions, commit) =>
                        partitions.toList
                          .traverse_ { partition =>
                            revocationCallbacks.get(partition).traverse { callback =>
                              Deferred[F, Unit].flatMap { revocationCompletion =>
                                callback(revocationCompletion.complete(())) >> revocationCompletion.get
                              }
                            }
                          } >> commit.as((List.empty, revocationCallbacks -- partitions))
                      case RebalanceCommand.AssignPartitions(partitions, commit) =>
                        partitions.toList
                          .traverse { p =>
                            Deferred[F, F[Unit]].flatMap { x =>
                              Ref[F].of(false).map { cancelled =>
                                (
                                  AssignedPartition(p, pc, x.get.guaranteeCase {
                                    case ExitCase.Canceled => cancelled.set(true)
                                    case _                 => ().pure[F]
                                  }),
                                  (complete: F[Unit]) =>
                                    cancelled.get.ifM(complete, x.complete(complete))
                                )
                              }
                            }
                          }
                          .flatMap { list =>
                            val assignedPartitions = list.map(_._1)
                            val updatedRevocationCallbacks = revocationCallbacks ++ list.map(
                              x => (x._1.partition, x._2)
                            )
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

}
