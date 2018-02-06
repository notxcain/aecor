package aecor.schedule.protobuf

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import aecor.runtime.akkapersistence.serialization.Codec
import aecor.schedule.ScheduleEvent
import aecor.schedule.serialization.protobuf.msg.{ ScheduleEntryAdded, ScheduleEntryFired }

import scala.util.{ Failure, Try }

object ScheduleEventCodec extends Codec[ScheduleEvent] {
  val ScheduleEntryAddedManifest = "A"
  val ScheduleEntryFiredManifest = "B"

  override def manifest(o: ScheduleEvent): String = o match {
    case e: ScheduleEvent.ScheduleEntryAdded => ScheduleEntryAddedManifest
    case e: ScheduleEvent.ScheduleEntryFired => ScheduleEntryFiredManifest
  }

  override def decode(bytes: Array[Byte], manifest: String): Try[ScheduleEvent] = manifest match {
    case ScheduleEntryAddedManifest =>
      ScheduleEntryAdded.validate(bytes).map {
        case ScheduleEntryAdded(entryId, correlationId, dueToInEpochMillisUTC, timestamp) =>
          val dateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(dueToInEpochMillisUTC), ZoneOffset.UTC)
          ScheduleEvent
            .ScheduleEntryAdded(entryId, correlationId, dateTime, Instant.ofEpochMilli(timestamp))
      }
    case ScheduleEntryFiredManifest =>
      ScheduleEntryFired.validate(bytes).map {
        case ScheduleEntryFired(entryId, correlationId, timestamp) =>
          ScheduleEvent.ScheduleEntryFired(entryId, correlationId, Instant.ofEpochMilli(timestamp))
      }
    case other => Failure(new IllegalArgumentException(s"Unknown manifest [$other]"))
  }

  override def encode(o: ScheduleEvent): Array[Byte] = o match {
    case ScheduleEvent
          .ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp) =>
      ScheduleEntryAdded(
        entryId,
        correlationId,
        dueDate.toInstant(ZoneOffset.UTC).toEpochMilli,
        timestamp.toEpochMilli
      ).toByteArray
    case ScheduleEvent
          .ScheduleEntryFired(entryId, correlationId, timestamp) =>
      ScheduleEntryFired(entryId, correlationId, timestamp.toEpochMilli).toByteArray
  }
}
