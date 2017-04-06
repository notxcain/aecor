package aecor.schedule.protobuf

import aecor.runtime.akkapersistence.serialization.PersistentReprSerializer
import akka.actor.ExtendedActorSystem

/**
  * Serializer with id = 120 for backwards compatibilty
  *
  * @param system Actor system
  */
class ScheduleEventSerializer(system: ExtendedActorSystem) extends PersistentReprSerializer(system)
