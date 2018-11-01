package aecor.schedule

import java.time.{ Instant, LocalDateTime, ZoneOffset, ZonedDateTime }

import aecor.MonadActionLift
import aecor.data.{ ActionT, EventsourcedBehavior, Folded }
import aecor.data.Folded.syntax._
import aecor.runtime.akkapersistence.serialization.PersistentDecoder.DecodingResult
import aecor.runtime.akkapersistence.serialization.{
  PersistentDecoder,
  PersistentEncoder,
  PersistentRepr
}
import aecor.schedule.ScheduleEvent.{ ScheduleEntryAdded, ScheduleEntryFired }
import aecor.schedule.ScheduleState._
import aecor.schedule.serialization.protobuf.msg
import cats.implicits._
import cats.kernel.Eq
import cats.{ Functor, Monad }

import scala.util.{ Failure, Try }

object DefaultScheduleBucket {

  def apply[I[_], F[_]: Functor](
    clock: F[ZonedDateTime]
  )(implicit F: MonadActionLift[I, F, ScheduleState, ScheduleEvent]): ScheduleBucket[I] =
    new DefaultScheduleBucket(clock)

  def behavior[F[_]: Monad](
    clock: F[ZonedDateTime]
  ): EventsourcedBehavior[ScheduleBucket, F, ScheduleState, ScheduleEvent] =
    EventsourcedBehavior(
      DefaultScheduleBucket[ActionT[F, ScheduleState, ScheduleEvent, ?], F](clock)(
        Functor[F],
        ActionT.monadActionLiftInstance[F, ScheduleState, ScheduleEvent]
      ),
      ScheduleState.initial,
      _.update(_)
    )
}

class DefaultScheduleBucket[I[_], F[_]: Functor](clock: F[ZonedDateTime])(
  implicit F: MonadActionLift[I, F, ScheduleState, ScheduleEvent]
) extends ScheduleBucket[I] {

  import F._

  override def addScheduleEntry(entryId: String,
                                correlationId: String,
                                dueDate: LocalDateTime): I[Unit] =
    read.flatMap { state =>
      liftF(clock).flatMap { zdt =>
        val timestamp = zdt.toInstant
        val now = zdt.toLocalDateTime
        if (state.unfired.contains(entryId) || state.fired.contains(entryId)) {
          ().pure[I]
        } else {
          append(ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp)) >>
            whenA(dueDate.isEqual(now) || dueDate.isBefore(now)) {
              append(ScheduleEntryFired(entryId, correlationId, timestamp))
            }
        }
      }

    }

  override def fireEntry(entryId: String): I[Unit] =
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

  implicit val eq: Eq[ScheduleEvent] = Eq.fromUniversalEquals
}

trait ScheduleEventInstances {
  implicit val persistentEncoderDecoder
    : PersistentEncoder[ScheduleEvent] with PersistentDecoder[ScheduleEvent] =
    new PersistentEncoder[ScheduleEvent] with PersistentDecoder[ScheduleEvent] {
      val ScheduleEntryAddedManifest = "A"
      val ScheduleEntryFiredManifest = "B"

      private def manifest(o: ScheduleEvent): String = o match {
        case e: ScheduleEvent.ScheduleEntryAdded => ScheduleEntryAddedManifest
        case e: ScheduleEvent.ScheduleEntryFired => ScheduleEntryFiredManifest
      }

      private def tryDecode(bytes: Array[Byte], manifest: String): Try[ScheduleEvent] =
        manifest match {
          case ScheduleEntryAddedManifest =>
            msg.ScheduleEntryAdded.validate(bytes).map {
              case msg
                    .ScheduleEntryAdded(entryId, correlationId, dueToInEpochMillisUTC, timestamp) =>
                val dateTime =
                  LocalDateTime
                    .ofInstant(Instant.ofEpochMilli(dueToInEpochMillisUTC), ZoneOffset.UTC)
                ScheduleEvent
                  .ScheduleEntryAdded(
                    entryId,
                    correlationId,
                    dateTime,
                    Instant.ofEpochMilli(timestamp)
                  )
            }
          case ScheduleEntryFiredManifest =>
            msg.ScheduleEntryFired.validate(bytes).map {
              case msg.ScheduleEntryFired(entryId, correlationId, timestamp) =>
                ScheduleEvent
                  .ScheduleEntryFired(entryId, correlationId, Instant.ofEpochMilli(timestamp))
            }
          case other => Failure(new IllegalArgumentException(s"Unknown manifest [$other]"))
        }

      private def encodeEvent(o: ScheduleEvent): Array[Byte] = o match {
        case ScheduleEvent
              .ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp) =>
          msg
            .ScheduleEntryAdded(
              entryId,
              correlationId,
              dueDate.toInstant(ZoneOffset.UTC).toEpochMilli,
              timestamp.toEpochMilli
            )
            .toByteArray
        case ScheduleEvent
              .ScheduleEntryFired(entryId, correlationId, timestamp) =>
          msg.ScheduleEntryFired(entryId, correlationId, timestamp.toEpochMilli).toByteArray
      }
      override def encode(a: ScheduleEvent): PersistentRepr =
        PersistentRepr(manifest(a), encodeEvent(a))
      override def decode(repr: PersistentRepr): DecodingResult[ScheduleEvent] =
        DecodingResult.fromTry(tryDecode(repr.payload, repr.manifest))
    }
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
