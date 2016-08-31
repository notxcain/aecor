package aecor.schedule.serialization.protobuf

import java.time.{Instant, ZoneOffset}

import aecor.core.serialization.akka.Codec
import aecor.schedule.ScheduleEvent

import scala.util.Try

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
        case ScheduleEntryAdded(scheduleName, entryId, correlationId, dueToInEpochMillisUTC) =>
          val dateTime = Instant.ofEpochMilli(dueToInEpochMillisUTC).atOffset(ZoneOffset.UTC).toLocalDateTime
          ScheduleEvent.ScheduleEntryAdded(scheduleName, entryId, correlationId, dateTime)
      }
    case ScheduleEntryFiredManifest =>
      ScheduleEntryFired.validate(bytes).map {
        case ScheduleEntryFired(scheduleName, entryId, correlationId) =>
          ScheduleEvent.ScheduleEntryFired(scheduleName, entryId, correlationId)
      }
  }

  override def encode(o: ScheduleEvent): Array[Byte] = o match {
    case ScheduleEvent.ScheduleEntryAdded(scheduleName, entryId, correlationId, dueDate) =>
      ScheduleEntryAdded(scheduleName, entryId, correlationId, dueDate.toInstant(ZoneOffset.UTC).toEpochMilli).toByteArray
    case ScheduleEvent.ScheduleEntryFired(scheduleName, entryId, correlationId) =>
      ScheduleEntryFired(scheduleName, entryId, correlationId).toByteArray
  }
}
