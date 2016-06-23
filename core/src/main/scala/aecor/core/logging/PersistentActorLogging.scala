package aecor.core.logging

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.event.{LogSource, LoggingAdapter}
import akka.persistence.PersistenceIdentity

trait PersistentActorLogging extends ActorLogging { this: Actor with PersistenceIdentity =>
  implicit def logSource: LogSource[Actor with PersistenceIdentity] = new LogSource[Actor with PersistenceIdentity] {
    import LogSource._
    def genString(a: Actor with PersistenceIdentity) = s"${fromActor.genString(a)} [${a.persistenceId}]"
    override def genString(a: Actor with PersistenceIdentity, system: ActorSystem) = s"${fromActor.genString(a, system)} [${a.persistenceId}]"
  }
  private var _log: LoggingAdapter = _

  override def log: LoggingAdapter = {
    if (_log eq null)
      _log = akka.event.Logging(context.system, this)(logSource)
    _log
  }
}