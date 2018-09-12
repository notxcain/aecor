package aecor.schedule

import java.time.{ Instant, LocalDateTime, ZonedDateTime }

import aecor.data.Folded
import aecor.data.Folded.syntax._
import aecor.data.next._
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.ScheduleState._
import aecor.schedule.protobuf.ScheduleEventCodec
import cats.{ Functor, Monad }
import cats.implicits._

object DefaultScheduleBucket {

  def apply[F[_], G[_]: Functor](clock: G[ZonedDateTime])(
    implicit F: MonadActionLift[F, G, ScheduleState, ScheduleEvent, Void]
  ): ScheduleBucket[F] =
    new DefaultScheduleBucket(clock)

  def behavior[F[_]: Monad](
    clock: F[ZonedDateTime]
  ): EventsourcedBehavior[ScheduleBucket, F, ScheduleState, ScheduleEvent, Void] =
    EventsourcedBehavior[ScheduleBucket, F, ScheduleState, ScheduleEvent, Void](DefaultScheduleBucket(clock), ScheduleState.initial, _.update(_))
}

class DefaultScheduleBucket[F[_], G[_]: Functor](clock: G[ZonedDateTime])(
  implicit F: MonadActionLift[F, G, ScheduleState, ScheduleEvent, Void]
) extends ScheduleBucket[F] {

  import F._

  override def addScheduleEntry(entryId: String,
                                correlationId: String,
                                dueDate: LocalDateTime): F[Unit] =
    read.flatMap { state =>
      liftF(clock).flatMap { zdt =>
        val timestamp = zdt.toInstant
        val now = zdt.toLocalDateTime
        if (state.unfired.contains(entryId) || state.fired.contains(entryId)) {
          ().pure[F]
        } else {
          append(ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp)) >>
            whenA(dueDate.isEqual(now) || dueDate.isBefore(now)) {
              append(ScheduleEntryFired(entryId, correlationId, timestamp))
            }
        }
      }

    }

  override def fireEntry(entryId: String): F[Unit] =
    read.flatMap { state =>
      liftF(clock).map(_.toInstant).flatMap { timestamp =>
        state
          .findEntry(entryId)
          .traverse_(entry => append(ScheduleEntryFired(entry.id, entry.correlationId, timestamp)))
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
