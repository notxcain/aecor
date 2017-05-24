package aecor.schedule

import java.time.{ Instant, LocalDateTime, ZonedDateTime }

import aecor.data.Folded.syntax._
import aecor.data._
import aecor.effect.Capture
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.ScheduleState._
import aecor.schedule.protobuf.ScheduleEventCodec
import cats.Functor
import cats.implicits._

import scala.collection.immutable.Seq

object DefaultScheduleAggregate {

  def apply[F[_]: Functor](
    clock: F[ZonedDateTime]
  ): ScheduleAggregate[Handler[F, ScheduleState, Seq[ScheduleEvent], ?]] =
    new DefaultScheduleAggregate(clock)

  def correlation: Correlation[ScheduleOp] =
    c => CorrelationId.composite("-", c.scheduleName, c.scheduleBucket)

  def behavior[F[_]: Functor](
    clock: F[ZonedDateTime]
  ): EventsourcedBehavior[F, ScheduleOp, ScheduleState, ScheduleEvent] =
    EventsourcedBehavior(DefaultScheduleAggregate(clock).asFunctionK, ScheduleState.folder)

}

class DefaultScheduleAggregate[F[_]: Functor](clock: F[ZonedDateTime])
    extends ScheduleAggregate[Handler[F, ScheduleState, Seq[ScheduleEvent], ?]] {

  override def addScheduleEntry(
    scheduleName: String,
    scheduleBucket: String,
    entryId: String,
    correlationId: CorrelationId,
    dueDate: LocalDateTime
  ): Handler[F, ScheduleState, Seq[ScheduleEvent], Unit] =
    Handler { state =>
      clock.map { zdt =>
        val timestamp = zdt.toInstant
        val now = zdt.toLocalDateTime
        if (state.unfired.get(entryId).isDefined || state.fired.contains(entryId)) {
          Vector.empty -> (())
        } else {
          val scheduleEntryAdded = ScheduleEntryAdded(
            scheduleName,
            scheduleBucket,
            entryId,
            correlationId,
            dueDate,
            timestamp
          )
          val firedEvent = if (dueDate.isEqual(now) || dueDate.isBefore(now)) {
            Vector(
              ScheduleEntryFired(scheduleName, scheduleBucket, entryId, correlationId, timestamp)
            )
          } else {
            Vector.empty
          }

          (scheduleEntryAdded +: firedEvent, ())
        }
      }

    }
  override def fireEntry(scheduleName: String,
                         scheduleBucket: String,
                         entryId: String): Handler[F, ScheduleState, Seq[ScheduleEvent], Unit] =
    Handler { state =>
      clock.map(_.toInstant).map { timestamp =>
        state
          .findEntry(entryId)
          .map(
            entry =>
              ScheduleEntryFired(
                scheduleName,
                scheduleBucket,
                entry.id,
                entry.correlationId,
                timestamp
            )
          )
          .toVector -> (())
      }
    }
}

sealed abstract class ScheduleEvent extends Product with Serializable {
  def scheduleName: String
  def scheduleBucket: String
  def entryId: String
  def timestamp: Instant
}

object ScheduleEvent extends ScheduleEventInstances {
  final case class ScheduleEntryAdded(scheduleName: String,
                                      scheduleBucket: String,
                                      entryId: String,
                                      correlationId: CorrelationId,
                                      dueDate: LocalDateTime,
                                      timestamp: Instant)
      extends ScheduleEvent

  final case class ScheduleEntryFired(scheduleName: String,
                                      scheduleBucket: String,
                                      entryId: String,
                                      correlationId: CorrelationId,
                                      timestamp: Instant)
      extends ScheduleEvent
}

trait ScheduleEventInstances {
  implicit val persistentEncoder: PersistentEncoder[ScheduleEvent] =
    PersistentEncoder.fromCodec(ScheduleEventCodec)
  implicit val persistentDecoder: PersistentDecoder[ScheduleEvent] =
    PersistentDecoder.fromCodec(ScheduleEventCodec)
}

private[aecor] case class ScheduleState(unfired: Map[String, ScheduleEntry], fired: Set[String]) {
  def addEntry(entryId: String,
               correlationId: CorrelationId,
               dueDate: LocalDateTime): ScheduleState =
    copy(unfired = unfired + (entryId -> ScheduleEntry(entryId, correlationId, dueDate)))

  def markEntryAsFired(entryId: String): ScheduleState =
    copy(unfired = unfired - entryId, fired = fired + entryId)

  def findEntry(entryId: String): Option[ScheduleEntry] =
    unfired.get(entryId)

  def update(event: ScheduleEvent): Folded[ScheduleState] = event match {
    case ScheduleEntryAdded(_, _, entryId, correlationId, dueDate, _) =>
      addEntry(entryId, correlationId, dueDate).next
    case e: ScheduleEntryFired =>
      markEntryAsFired(e.entryId).next
  }
}

private[aecor] object ScheduleState {

  def initial: ScheduleState = ScheduleState(Map.empty, Set.empty)

  case class ScheduleEntry(id: String, correlationId: CorrelationId, dueDate: LocalDateTime)

  val folder: Folder[Folded, ScheduleEvent, ScheduleState] =
    Folder.curried(initial)(_.update)
}
