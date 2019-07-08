package aecor.old.schedule.protobuf

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import aecor.old.aggregate.serialization.Codec
import aecor.old.schedule.ScheduleEvent
import aecor.old.schedule.serialization.protobuf.msg.{ ScheduleEntryAdded, ScheduleEntryFired }

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
        case ScheduleEntryAdded(
            scheduleName,
            scheduleBucket,
            entryId,
            correlationId,
            dueToInEpochMillisUTC,
            timestamp
            ) =>
          val dateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(dueToInEpochMillisUTC), ZoneOffset.UTC)
          ScheduleEvent
            .ScheduleEntryAdded(
              scheduleName,
              scheduleBucket,
              entryId,
              correlationId,
              dateTime,
              Instant.ofEpochMilli(timestamp)
            )
      }
    case ScheduleEntryFiredManifest =>
      ScheduleEntryFired.validate(bytes).map {
        case ScheduleEntryFired(scheduleName, scheduleBucket, entryId, correlationId, timestamp) =>
          ScheduleEvent.ScheduleEntryFired(
            scheduleName,
            scheduleBucket,
            entryId,
            correlationId,
            Instant.ofEpochMilli(timestamp)
          )
      }
    case other => throw new IllegalArgumentException(s"Unexpected manifest [$other]")
  }

  override def encode(o: ScheduleEvent): Array[Byte] = o match {
    case ScheduleEvent
          .ScheduleEntryAdded(
          scheduleName,
          scheduleBucket,
          entryId,
          correlationId,
          dueDate,
          timestamp
          ) =>
      ScheduleEntryAdded(
        scheduleName,
        scheduleBucket,
        entryId,
        correlationId,
        dueDate.toInstant(ZoneOffset.UTC).toEpochMilli,
        timestamp.toEpochMilli
      ).toByteArray
    case ScheduleEvent
          .ScheduleEntryFired(scheduleName, scheduleBucket, entryId, correlationId, timestamp) =>
      ScheduleEntryFired(
        scheduleName,
        scheduleBucket,
        entryId,
        correlationId,
        timestamp.toEpochMilli
      ).toByteArray
  }
}
