package aecor.schedule

import java.time.{ Instant, LocalDateTime, ZonedDateTime }

import aecor.data.Folded.syntax._
import aecor.data._
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.ScheduleState._
import aecor.schedule.protobuf.ScheduleEventCodec
import cats.Functor
import cats.implicits._

object DefaultScheduleBucket {

  def apply[F[_]: Functor](
    clock: F[ZonedDateTime]
  ): ScheduleBucket[Handler[F, ScheduleState, ScheduleEvent, ?]] =
    new DefaultScheduleBucket(clock)

  def behavior[F[_]: Functor](
    clock: F[ZonedDateTime]
  ): EventsourcedBehavior[F, ScheduleOp, ScheduleState, ScheduleEvent] =
    EventsourcedBehavior(DefaultScheduleBucket(clock).asFunctionK, ScheduleState.folder)

}

class DefaultScheduleBucket[F[_]: Functor](clock: F[ZonedDateTime])
    extends ScheduleBucket[Handler[F, ScheduleState, ScheduleEvent, ?]] {

  override def addScheduleEntry(
    entryId: String,
    correlationId: String,
    dueDate: LocalDateTime
  ): Handler[F, ScheduleState, ScheduleEvent, Unit] =
    Handler { state =>
      clock.map { zdt =>
        val timestamp = zdt.toInstant
        val now = zdt.toLocalDateTime
        if (state.unfired.get(entryId).isDefined || state.fired.contains(entryId)) {
          Vector.empty -> (())
        } else {
          val scheduleEntryAdded = ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp)
          val firedEvent = if (dueDate.isEqual(now) || dueDate.isBefore(now)) {
            Vector(ScheduleEntryFired(entryId, correlationId, timestamp))
          } else {
            Vector.empty
          }

          (scheduleEntryAdded +: firedEvent, ())
        }
      }

    }
  override def fireEntry(entryId: String): Handler[F, ScheduleState, ScheduleEvent, Unit] =
    Handler { state =>
      clock.map(_.toInstant).map { timestamp =>
        state
          .findEntry(entryId)
          .map(entry => ScheduleEntryFired(entry.id, entry.correlationId, timestamp))
          .toVector -> (())
      }
    }
}

sealed abstract class ScheduleEvent extends Product with Serializable {
  def entryId: String
  def timestamp: Instant
}

object ScheduleEvent extends ScheduleEventInstances {
  final case class ScheduleEntryAdded(entryId: String,
                                      correlationId: String,
                                      dueDate: LocalDateTime,
                                      timestamp: Instant)
      extends ScheduleEvent

  final case class ScheduleEntryFired(entryId: String, correlationId: String, timestamp: Instant)
      extends ScheduleEvent
}

trait ScheduleEventInstances {
  implicit val persistentEncoder: PersistentEncoder[ScheduleEvent] =
    PersistentEncoder.fromCodec(ScheduleEventCodec)
  implicit val persistentDecoder: PersistentDecoder[ScheduleEvent] =
    PersistentDecoder.fromCodec(ScheduleEventCodec)
}

private[aecor] case class ScheduleState(unfired: Map[String, ScheduleEntry], fired: Set[String]) {
  def addEntry(entryId: String, correlationId: String, dueDate: LocalDateTime): ScheduleState =
    copy(unfired = unfired + (entryId -> ScheduleEntry(entryId, correlationId, dueDate)))

  def markEntryAsFired(entryId: String): ScheduleState =
    copy(unfired = unfired - entryId, fired = fired + entryId)

  def findEntry(entryId: String): Option[ScheduleEntry] =
    unfired.get(entryId)

  def update(event: ScheduleEvent): Folded[ScheduleState] = event match {
    case ScheduleEntryAdded(entryId, correlationId, dueDate, _) =>
      addEntry(entryId, correlationId, dueDate).next
    case e: ScheduleEntryFired =>
      markEntryAsFired(e.entryId).next
  }
}

private[aecor] object ScheduleState {

  def initial: ScheduleState = ScheduleState(Map.empty, Set.empty)

  case class ScheduleEntry(id: String, correlationId: String, dueDate: LocalDateTime)

  val folder: Folder[Folded, ScheduleEvent, ScheduleState] =
    Folder.curried(initial)(_.update)
}
