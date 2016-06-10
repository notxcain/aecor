package aecor.core.serialization

import aecor.core.entity.EntityEventEnvelope
import akka.persistence.journal.{Tagged, WriteEventAdapter}

class EntityEventTagger extends WriteEventAdapter {
  override def manifest(event: Any): String = ""
  override def toJournal(event: Any): Any = event match {
    case eee: EntityEventEnvelope[_] => Tagged(eee, Set(eee.entityName))
    case other => throw new IllegalArgumentException(s"EntityEventTagger should be used only for EntityEventEnvelope[A], got $other")
  }
}
