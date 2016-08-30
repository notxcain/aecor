package aecor.schedule.serialization.protobuf

import java.time.{Instant, ZoneOffset}

import aecor.core.serialization.akka.Codec
import aecor.schedule.{Payload, ScheduleEvent}
import com.google.protobuf.ByteString

import scala.util.Try

class ScheduleEventCodec extends Codec[ScheduleEvent] {
  val ScheduleEntryAddedManifest = "A"
  val ScheduleEntryFiredManifest = "B"

  override def manifest(o: ScheduleEvent): String = o match {
    case e: ScheduleEvent.ScheduleEntryAdded => ScheduleEntryAddedManifest
    case e: ScheduleEvent.ScheduleEntryFired => ScheduleEntryFiredManifest
  }

  override def decode(bytes: Array[Byte], manifest: String): Try[ScheduleEvent] = manifest match {
    case ScheduleEntryAddedManifest =>
      ScheduleEntryAdded.validate(bytes).map {
        case ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueToInEpochSeconds) =>

          val dateTime = Instant.ofEpochSecond(dueToInEpochSeconds).atOffset(ZoneOffset.UTC).toLocalDateTime
          ScheduleEvent.ScheduleEntryAdded(scheduleName, entryId, correlationId, Payload(payload.map(_.toByteArray)), dateTime)
      }
    case ScheduleEntryFiredManifest =>
      ScheduleEntryFired.validate(bytes).map {
        case ScheduleEntryFired(scheduleName, entryId, correlationId, payload) =>
          ScheduleEvent.ScheduleEntryFired(scheduleName, entryId, correlationId, Payload(payload.map(_.toByteArray)))
      }
  }

  override def encode(o: ScheduleEvent): Array[Byte] = o match {
    case ScheduleEvent.ScheduleEntryAdded(scheduleName, entryId, correlationId, payload, dueDate) =>
      ScheduleEntryAdded(scheduleName, entryId, correlationId, payload.value.map(ByteString.copyFrom), dueDate.toEpochSecond(ZoneOffset.UTC)).toByteArray
    case ScheduleEvent.ScheduleEntryFired(scheduleName, entryId, correlationId, payload) =>
      ScheduleEntryFired(scheduleName, entryId, correlationId, payload.value.map(ByteString.copyFrom)).toByteArray
  }
}
