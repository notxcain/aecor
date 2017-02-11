package aecor.schedule.aggregate

import java.time.LocalDateTime

import aecor.aggregate._
import aecor.aggregate.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.data.Folded.syntax._
import aecor.data.{ Folded, Handler }
import aecor.schedule.aggregate.ScheduleCommand.{ AddScheduleEntry, FireEntry }
import aecor.schedule.aggregate.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.protobuf.ScheduleEventCodec
import cats.arrow.FunctionK
import cats.~>
import cats.implicits._
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

sealed abstract class ScheduleCommand[A] {
  def scheduleName: String
  def entryId: String
}
object ScheduleCommand {
  final case class AddScheduleEntry(scheduleName: String,
                                    entryId: String,
                                    correlationId: CorrelationId,
                                    dueDate: LocalDateTime)
      extends ScheduleCommand[Unit]

  final case class FireEntry(scheduleName: String, entryId: String) extends ScheduleCommand[Unit]
}

private[aecor] case class ScheduleState(correlationId: CorrelationId,
                                        dueDate: LocalDateTime,
                                        fired: Boolean)

object ScheduleState {
  implicit val folder: Folder[Folded, ScheduleEvent, Option[ScheduleState]] =
    Folder.instance(Option.empty[ScheduleState]) {
      case None => {
        case ScheduleEntryAdded(_, _, correlationId, dueDate) =>
          ScheduleState(correlationId, dueDate, fired = false).some.next
        case _ =>
          impossible
      }
      case Some(state) => {
        case _: ScheduleEntryAdded =>
          impossible
        case _: ScheduleEntryFired =>
          state.copy(fired = true).some.next
      }
    }
}

trait ScheduleAggregate[F[_]] {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): F[Unit]

  def fireEntry(scheduleName: String, entryId: String): F[Unit]
  def asFunctionK: ScheduleCommand ~> F =
    λ[ScheduleCommand ~> F] {
      case AddScheduleEntry(scheduleName, entryId, correlationId, dueDate) =>
        addScheduleEntry(scheduleName, entryId, correlationId, dueDate)
      case FireEntry(scheduleName, entryId) =>
        fireEntry(scheduleName, entryId)
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

      override def fireEntry(scheduleName: String, entryId: String): F[Unit] =
        f(FireEntry(scheduleName, entryId))
    }
}

object DefaultScheduleAggregate
    extends ScheduleAggregate[Handler[Option[ScheduleState], ScheduleEvent, ?]] {

  def correlation(bucketLength: FiniteDuration): Correlation[ScheduleCommand] = {
    def mk[A](c: ScheduleCommand[A]): CorrelationIdF[A] =
      CorrelationId.composite("::", c.scheduleName, c.entryId)
    FunctionK.lift(mk _)
  }

  override def addScheduleEntry(
    scheduleName: String,
    entryId: String,
    correlationId: CorrelationId,
    dueDate: LocalDateTime
  ): Handler[Option[ScheduleState], ScheduleEvent, Unit] =
    Handler {
      case Some(state) =>
        Vector.empty -> (())
      case None =>
        Vector(ScheduleEntryAdded(scheduleName, entryId, correlationId, dueDate)) -> (())
    }
  override def fireEntry(scheduleName: String,
                         entryId: String): Handler[Option[ScheduleState], ScheduleEvent, Unit] =
    Handler {
      case Some(state) =>
        Vector(ScheduleEntryFired(scheduleName, entryId, state.correlationId)) -> (())
      case None =>
        Vector.empty -> (())
    }
}
