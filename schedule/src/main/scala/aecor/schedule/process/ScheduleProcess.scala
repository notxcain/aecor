package aecor.schedule.process

import java.time._
import java.time.temporal.ChronoUnit

import aecor.aggregate.runtime.Async.ops._
import aecor.aggregate.runtime.{ Async, Capture, CaptureFuture }
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.{ ScheduleAggregate, ScheduleEntryRepository }
import aecor.streaming.StreamSupervisor.StreamKillSwitch
import aecor.streaming._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import cats.Monad
import cats.implicits._

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

object ScheduleProcess {
  def apply[F[_]: Monad](ops: ScheduleProcessOps[F],
                         eventualConsistencyDelay: FiniteDuration,
                         repository: ScheduleEntryRepository[F],
                         scheduleAggregate: ScheduleAggregate[F]): F[Unit] = {
    def updateRepository: F[Unit] =
      ops.processNewEvents {
        case ScheduleEntryAdded(scheduleName, scheduleBucket, entryId, _, dueDate, _) =>
          repository
            .insertScheduleEntry(scheduleName, scheduleBucket, entryId, dueDate)
        case ScheduleEntryFired(scheduleName, scheduleBucket, entryId, _, _) =>
          repository.markScheduleEntryAsFired(scheduleName, scheduleBucket, entryId)
      }
    def fireEntries(from: LocalDateTime, to: LocalDateTime) =
      ops.processEntries(from, to) { entry =>
        if (entry.fired)
          ().pure[F]
        else
          scheduleAggregate
            .fireEntry(entry.scheduleName, entry.scheduleBucket, entry.entryId)
      }

    for {
      _ <- updateRepository
      from <- ops.loadOffset
      now <- ops.now
      entry <- fireEntries(from.minus(eventualConsistencyDelay.toMillis, ChronoUnit.MILLIS), now)
      _ <- entry.map(_.dueDate).traverse(ops.saveOffset)
    } yield ()
  }

}

class ScheduleProcessRuntime[F[_]: Async: CaptureFuture: Capture: Monad](
  entityName: String,
  refreshInterval: FiniteDuration,
  processCycle: F[Unit]
)(implicit materializer: Materializer) {

  private def source =
    Source
      .single(())
      .mapAsync(1) { _ =>
        eachRefreshInterval(processCycle).unsafeRun
      }

  private def eachRefreshInterval(f: F[Unit]): F[Unit] =
    for {
      _ <- f
      _ <- afterRefreshInterval {
            eachRefreshInterval(f)
          }
    } yield ()

  private def afterRefreshInterval[A](f: => F[A]): F[A] =
    CaptureFuture[F].captureF {
      val p = Promise[A]
      materializer.scheduleOnce(refreshInterval, new Runnable {
        override def run(): Unit = {
          p.completeWith(f.unsafeRun)
          ()
        }
      })
      p.future
    }

  def run(system: ActorSystem): F[StreamKillSwitch[F]] =
    StreamSupervisor(system)
      .startClusterSingleton(s"$entityName-Process", source, Flow[Unit])
}

object ScheduleProcessRuntime {
  def apply[F[_]: Async: CaptureFuture: Capture: Monad](
    entityName: String,
    refreshInterval: FiniteDuration,
    processCycle: F[Unit]
  )(implicit materializer: Materializer): ScheduleProcessRuntime[F] =
    new ScheduleProcessRuntime[F](entityName, refreshInterval, processCycle)
}
