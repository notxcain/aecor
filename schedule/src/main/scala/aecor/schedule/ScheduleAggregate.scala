package aecor.schedule

import java.time.{ Instant, LocalDateTime }

import aecor.aggregate._
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.data.Folded
import aecor.data.Folded.syntax._
import aecor.schedule.ScheduleOp.{ AddScheduleEntry, FireEntry }
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.ScheduleState.ScheduleEntry
import aecor.schedule.protobuf.ScheduleEventCodec
import cats.~>

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

sealed abstract class ScheduleOp[A] extends Product with Serializable {
  def scheduleName: String
  def scheduleBucket: String
}

object ScheduleOp {
  final case class AddScheduleEntry(scheduleName: String,
                                    scheduleBucket: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime)
      extends ScheduleOp[Unit]

  final case class FireEntry(scheduleName: String, scheduleBucket: String, entryId: String)
      extends ScheduleOp[Unit]
}

private[aecor] case class ScheduleState(entries: Map[String, ScheduleEntry]) {
  def addEntry(entryId: String,
               correlationId: CorrelationId,
               dueDate: LocalDateTime): ScheduleState =
    copy(entries = entries + (entryId -> ScheduleEntry(entryId, correlationId, dueDate)))

  def removeEntry(entryId: String): ScheduleState =
    copy(entries = entries - entryId)

  def findEntry(entryId: String): Option[ScheduleEntry] =
    entries.get(entryId)

  def update(event: ScheduleEvent): Folded[ScheduleState] = event match {
    case ScheduleEntryAdded(_, _, entryId, correlationId, dueDate, _) =>
      addEntry(entryId, correlationId, dueDate).next
    case e: ScheduleEntryFired =>
      removeEntry(e.entryId).next
  }
}

private[aecor] object ScheduleState {

  def initial: ScheduleState = ScheduleState(Map.empty)

  case class ScheduleEntry(id: String, correlationId: CorrelationId, dueDate: LocalDateTime)

  implicit val folder: Folder[Folded, ScheduleEvent, ScheduleState] =
    Folder.instance(ScheduleState(Map.empty))(_.update)
}

private[aecor] trait ScheduleAggregate[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       scheduleBucket: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]

  def fireEntry(scheduleName: String, scheduleBucket: String, entryId: String): F[Unit]

  def asFunctionK: ScheduleOp ~> F =
    λ[ScheduleOp ~> F] {
      case AddScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate) =>
        addScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate)
      case FireEntry(scheduleName, scheduleBucket, entryId) =>
        fireEntry(scheduleName, scheduleBucket, entryId)
    }
}

private[aecor] object ScheduleAggregate {
  def fromFunctionK[F[_]](f: ScheduleOp ~> F): ScheduleAggregate[F] =
    new ScheduleAggregate[F] {
      override def addScheduleEntry(scheduleName: String,
                                    scheduleBucket: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime): F[Unit] =
        f(AddScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate))

      override def fireEntry(scheduleName: String,
                             scheduleBucket: String,
                             entryId: String): F[Unit] =
        f(FireEntry(scheduleName, scheduleBucket, entryId))
    }
}
