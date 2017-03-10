package aecor.schedule

import java.time.{ Clock, Instant, LocalDateTime }

import aecor.aggregate._
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.data.Folded.syntax._
import aecor.data.{ Folded, Handler }
import aecor.schedule.ScheduleCommand.{ AddScheduleEntry, FireEntry }
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.ScheduleState.ScheduleEntry
import aecor.schedule.protobuf.ScheduleEventCodec
import cats.arrow.FunctionK
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

sealed abstract class ScheduleCommand[A] extends Product with Serializable
object ScheduleCommand {
  final case class AddScheduleEntry(scheduleName: String,
                                    scheduleBucket: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime)
      extends ScheduleCommand[Unit]

  final case class FireEntry(scheduleName: String, scheduleBucket: String, entryId: String)
      extends ScheduleCommand[Unit]
}

private[aecor] case class ScheduleState(entries: List[ScheduleEntry], ids: Set[String]) {
  def addEntry(entryId: String,
               correlationId: CorrelationId,
               dueDate: LocalDateTime): ScheduleState =
    copy(entries = ScheduleEntry(entryId, correlationId, dueDate) :: entries, ids = ids + entryId)

  def removeEntry(entryId: String): ScheduleState =
    copy(entries = entries.filterNot(_.id == entryId))

  def findEntry(entryId: String): Option[ScheduleEntry] =
    entries.collectFirst {
      case x if x.id == entryId => x
    }

  def update(event: ScheduleEvent): Folded[ScheduleState] = event match {
    case ScheduleEntryAdded(_, _, entryId, correlationId, dueDate, _) =>
      addEntry(entryId, correlationId, dueDate).next
    case e: ScheduleEntryFired =>
      removeEntry(e.entryId).next
  }
}

private[schedule] object ScheduleState {

  case class ScheduleEntry(id: String, correlationId: CorrelationId, dueDate: LocalDateTime)

  implicit val folder: Folder[Folded, ScheduleEvent, ScheduleState] =
    Folder.instance(ScheduleState(List.empty, Set.empty))(_.update)
}

private[schedule] trait ScheduleAggregate[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       scheduleBucket: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]

  def fireEntry(scheduleName: String, scheduleBucket: String, entryId: String): F[Unit]

  def asFunctionK: ScheduleCommand ~> F =
    λ[ScheduleCommand ~> F] {
      case AddScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate) =>
        addScheduleEntry(scheduleName, scheduleBucket, entryId, correlationId, dueDate)
      case FireEntry(scheduleName, scheduleBucket, entryId) =>
        fireEntry(scheduleName, scheduleBucket, entryId)
    }
}

private[schedule] object ScheduleAggregate {
  def fromFunctionK[F[_]](f: ScheduleCommand ~> F): ScheduleAggregate[F] =
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

private[schedule] object DefaultScheduleAggregate {

  def apply(clock: Clock): ScheduleAggregate[Handler[ScheduleState, ScheduleEvent, ?]] =
    new DefaultScheduleAggregate(clock)

  def correlation: Correlation[ScheduleCommand] = {
    def mk[A](c: ScheduleCommand[A]): CorrelationIdF[A] =
      c match {
        case AddScheduleEntry(scheduleName, scheduleBucket, _, _, _) =>
          CorrelationId.composite("-", scheduleName, scheduleBucket)
        case FireEntry(scheduleName, scheduleBucket, _) =>
          CorrelationId.composite("-", scheduleName, scheduleBucket)
      }
    FunctionK.lift(mk _)
  }

}

private[schedule] class DefaultScheduleAggregate(clock: Clock)
    extends ScheduleAggregate[Handler[ScheduleState, ScheduleEvent, ?]] {

  private def timestamp = clock.instant()

  override def addScheduleEntry(
    scheduleName: String,
    scheduleBucket: String,
    entryId: String,
    correlationId: CorrelationId,
    dueDate: LocalDateTime
  ): Handler[ScheduleState, ScheduleEvent, Unit] =
    Handler { state =>
      if (state.ids.contains(entryId)) {
        Vector.empty -> (())
      } else {
        val now = LocalDateTime.now(clock)
        val fired = if (dueDate.isEqual(now) || dueDate.isBefore(now)) {
          Vector(
            ScheduleEntryFired(scheduleName, scheduleBucket, entryId, correlationId, timestamp)
          )
        } else {
          Vector.empty
        }
        (Vector(
          ScheduleEntryAdded(
            scheduleName,
            scheduleBucket,
            entryId,
            correlationId,
            dueDate,
            timestamp
          )
        ) ++ fired) -> (())
      }
    }
  override def fireEntry(scheduleName: String,
                         scheduleBucket: String,
                         entryId: String): Handler[ScheduleState, ScheduleEvent, Unit] =
    Handler { state =>
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
