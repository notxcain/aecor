package aecor.schedule.serialization

import aecor.aggregate.serialization.PersistentReprSerializer
import akka.actor.ExtendedActorSystem

/**
  * Serializer with id = 120 for backwards compatibilty
  *
  * @param system Actor system
  */
class OldSerializer120(system: ExtendedActorSystem) extends PersistentReprSerializer(system)
