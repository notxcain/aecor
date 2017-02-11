package aecor.schedule.aggregate

import java.time.{ LocalDateTime, ZoneId }

import aecor.aggregate._
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.data.Folded.syntax._
import aecor.data.{ Folded, Handler }
import aecor.schedule.aggregate.ScheduleCommand.{ AddScheduleEntry, FireDueEntries }
import aecor.schedule.aggregate.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.aggregate.ScheduleState.ScheduleEntry
import aecor.schedule.protobuf.ScheduleEventCodec
import cats.arrow.FunctionK
import cats.~>

import scala.concurrent.duration._

sealed trait ScheduleEvent {
  def scheduleName: String
}

object ScheduleEvent extends ScheduleEventInstances {
  case class ScheduleEntryAdded(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId,
                                dueDate: LocalDateTime)
      extends ScheduleEvent

  case class ScheduleEntryFired(scheduleName: String,
                                entryId: String,
                                correlationId: CorrelationId)
      extends ScheduleEvent
}

trait ScheduleEventInstances {
  implicit val persistentEncoder: PersistentEncoder[ScheduleEvent] =
    PersistentEncoder.fromCodec(ScheduleEventCodec)
  implicit val persistentDecoder: PersistentDecoder[ScheduleEvent] =
    PersistentDecoder.fromCodec(ScheduleEventCodec)
}

sealed abstract class ScheduleCommand[A]
object ScheduleCommand {
  final case class AddScheduleEntry(scheduleName: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime)
      extends ScheduleCommand[Unit]

  final case class FireDueEntries(scheduleName: String, now: LocalDateTime)
      extends ScheduleCommand[Unit]
}

private[aecor] case class ScheduleState(entries: List[ScheduleEntry], ids: Set[String]) {
  def addEntry(entryId: String,
               correlationId: CorrelationId,
               dueDate: LocalDateTime): ScheduleState =
    copy(entries = ScheduleEntry(entryId, correlationId, dueDate) :: entries, ids = ids + entryId)

  def removeEntry(entryId: String): ScheduleState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntriesDueTo(date: LocalDateTime): List[ScheduleEntry] =
    entries.filter(_.dueDate.isBefore(date))

  def update(event: ScheduleEvent): Folded[ScheduleState] = event match {
    case ScheduleEntryAdded(scheduleName, entryId, correlationId, dueDate) =>
      addEntry(entryId, correlationId, dueDate).next
    case e: ScheduleEntryFired =>
      removeEntry(e.entryId).next
  }
}

object ScheduleState {

  private[aggregate] case class ScheduleEntry(id: String,
                                              correlationId: CorrelationId,
                                              dueDate: LocalDateTime)

  implicit val folder: Folder[Folded, ScheduleEvent, ScheduleState] =
    Folder.instance(ScheduleState(List.empty, Set.empty))(_.update)
}

trait ScheduleAggregate[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]

  def fireDueEntries(scheduleName: String, now: LocalDateTime): F[Unit]
  def asFunctionK: ScheduleCommand ~> F =
    λ[ScheduleCommand ~> F] {
      case AddScheduleEntry(scheduleName, entryId, correlationId, dueDate) =>
        addScheduleEntry(scheduleName, entryId, correlationId, dueDate)
      case FireDueEntries(scheduleName, now) =>
        fireDueEntries(scheduleName, now)
    }
}

object ScheduleAggregate {
  def fromFunctionK[F[_]](f: ScheduleCommand ~> F): ScheduleAggregate[F] =
    new ScheduleAggregate[F] {
      override def addScheduleEntry(scheduleName: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime): F[Unit] =
        f(AddScheduleEntry(scheduleName, entryId, correlationId, dueDate))
      override def fireDueEntries(scheduleName: String, now: LocalDateTime): F[Unit] =
        f(FireDueEntries(scheduleName, now))
    }
}

object DefaultScheduleAggregate
    extends ScheduleAggregate[Handler[ScheduleState, ScheduleEvent, ?]] {

  def correlation(bucketLength: FiniteDuration): Correlation[ScheduleCommand] = {
    def timeBucket(date: LocalDateTime) =
      date.atZone(ZoneId.systemDefault()).toEpochSecond / bucketLength.toSeconds

    def bucketId(scheduleName: String, dueDate: LocalDateTime) =
      s"$scheduleName-${timeBucket(dueDate)}"

    def mk[A](c: ScheduleCommand[A]): CorrelationIdF[A] =
      c match {
        case AddScheduleEntry(scheduleName, _, _, dueDate) =>
          bucketId(scheduleName, dueDate)
        case FireDueEntries(scheduleName, now) =>
          bucketId(scheduleName, now)
      }
    FunctionK.lift(mk _)
  }

  override def addScheduleEntry(
    scheduleName: String,
    entryId: String,
    correlationId: CorrelationId,
    dueDate: LocalDateTime
  ): Handler[ScheduleState, ScheduleEvent, Unit] =
    Handler { state =>
      if (state.ids.contains(entryId)) {
        Vector.empty -> (())
      } else {
        Vector(ScheduleEntryAdded(scheduleName, entryId, correlationId, dueDate)) -> (())
      }

    }
  override def fireDueEntries(scheduleName: String,
                              now: LocalDateTime): Handler[ScheduleState, ScheduleEvent, Unit] =
    Handler { state =>
      state
        .findEntriesDueTo(now)
        .map(entry => ScheduleEntryFired(scheduleName, entry.id, entry.correlationId))
        .toVector -> (())
    }
}
