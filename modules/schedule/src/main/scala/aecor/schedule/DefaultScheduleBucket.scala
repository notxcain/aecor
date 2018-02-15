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
  ): ScheduleBucket[ActionT[F, ScheduleState, ScheduleEvent, ?]] =
    new DefaultScheduleBucket(clock)

  def behavior[F[_]: Functor](
    clock: F[ZonedDateTime]
  ): EventsourcedBehaviorT[ScheduleBucket, F, ScheduleState, ScheduleEvent] =
    EventsourcedBehaviorT(DefaultScheduleBucket(clock), ScheduleState.initial, _.update(_))
}

class DefaultScheduleBucket[F[_]: Functor](clock: F[ZonedDateTime])
    extends ScheduleBucket[ActionT[F, ScheduleState, ScheduleEvent, ?]] {

  override def addScheduleEntry(
    entryId: String,
    correlationId: String,
    dueDate: LocalDateTime
  ): ActionT[F, ScheduleState, ScheduleEvent, Unit] =
    ActionT { state =>
      clock.map { zdt =>
        val timestamp = zdt.toInstant
        val now = zdt.toLocalDateTime
        if (state.unfired.get(entryId).isDefined || state.fired.contains(entryId)) {
          List.empty -> (())
        } else {
          val scheduleEntryAdded = ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp)
          val firedEvent = if (dueDate.isEqual(now) || dueDate.isBefore(now)) {
            List(ScheduleEntryFired(entryId, correlationId, timestamp))
          } else {
            List.empty
          }
          (scheduleEntryAdded :: firedEvent, ())
        }
      }

    }

  override def fireEntry(entryId: String): ActionT[F, ScheduleState, ScheduleEvent, Unit] =
    ActionT { state =>
      clock.map(_.toInstant).map { timestamp =>
        state
          .findEntry(entryId)
          .map(entry => ScheduleEntryFired(entry.id, entry.correlationId, timestamp))
          .toList -> (())
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
}
