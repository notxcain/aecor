package aecor.tests.e2e

import java.time.LocalDateTime

import aecor.schedule.CassandraScheduleEntryRepository.TimeBucket
import aecor.schedule.{ ScheduleBucketId, ScheduleEntryRepository }
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.testkit._
import monocle.Lens
import cats.mtl.MonadState
import cats.implicits._

import monocle.Lens

object TestScheduleEntryRepository {
  def apply[F[_]: MonadState[?[_], S], S](
    lens: Lens[S, Vector[ScheduleEntry]]
  ): ScheduleEntryRepository[F] =
    new TestScheduleEntryRepository(lens)
}

class TestScheduleEntryRepository[F[_]: MonadState[?[_], S], S](
  lens: Lens[S, Vector[ScheduleEntry]]
) extends ScheduleEntryRepository[F] {
  val F = lens.transformMonadState(MonadState[F, S])
  implicit val monad = F.monad
  override def insertScheduleEntry(scheduleBucketId: ScheduleBucketId,
                                   entryId: String,
                                   dueDate: LocalDateTime): F[Unit] =
    F.modify { scheduleEntries =>
      scheduleEntries :+ ScheduleEntry(
        scheduleBucketId,
        entryId,
        dueDate,
        TimeBucket(dueDate.toLocalDate).key,
        false
      )
    }
  override def markScheduleEntryAsFired(bucketId: ScheduleBucketId, entryId: String): F[Unit] =
    F.modify { scheduleEntries =>
      scheduleEntries.map { e =>
        if (e.bucketId == bucketId && e.entryId == entryId) {
          e.copy(fired = true)
        } else {
          e
        }
      }
    }

  override def processEntries(from: LocalDateTime, to: LocalDateTime, parallelism: Int)(
    f: (ScheduleEntryRepository.ScheduleEntry) => F[Unit]
  ): F[Option[ScheduleEntryRepository.ScheduleEntry]] =
    F.get.flatMap { entries =>
      entries.foldLeft(none[ScheduleEntry].pure[F]) { (acc, entry) =>
        if (entry.dueDate.isAfter(from) && (entry.dueDate.isBefore(to) || entry.dueDate == to)) {
          acc.flatMap(_ => f(entry)).map(_ => entry.some)
        } else {
          acc
        }
      }
    }
}
