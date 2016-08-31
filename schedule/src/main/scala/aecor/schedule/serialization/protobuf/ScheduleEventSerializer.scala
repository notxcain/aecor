package aecor.schedule.serialization.protobuf

import aecor.core.serialization.akka.CodecSerializer
import aecor.schedule.ScheduleEvent
import akka.actor.ExtendedActorSystem

class ScheduleEventSerializer(system: ExtendedActorSystem) extends CodecSerializer[ScheduleEvent](system, ScheduleEventCodec)
