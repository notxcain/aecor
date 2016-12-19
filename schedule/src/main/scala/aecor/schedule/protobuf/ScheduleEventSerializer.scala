package aecor.schedule.protobuf

import aecor.serialization.akka.CodecSerializer
import aecor.schedule.ScheduleEvent
import akka.actor.ExtendedActorSystem

class ScheduleEventSerializer(system: ExtendedActorSystem)
    extends CodecSerializer[ScheduleEvent](system, ScheduleEventCodec)
