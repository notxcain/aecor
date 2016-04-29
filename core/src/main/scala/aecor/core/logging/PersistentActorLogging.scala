package aecor.core.logging

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.event.{LogSource, LoggingAdapter}
import akka.persistence.{PersistenceIdentity, PersistentActor}

trait PersistentActorLogging extends ActorLogging { this: PersistentActor =>
  implicit def logSource[A <: Actor with PersistenceIdentity]: LogSource[A] = new LogSource[A] {
    import LogSource._
    def genString(a: A) = s"${fromActorRef.genString(a.self)} [${a.persistenceId}]"
    override def genString(a: A, system: ActorSystem) = s"${fromActorRef.genString(a.self, system)} [${a.persistenceId}]"
  }
  private var _log: LoggingAdapter = _

  override def log: LoggingAdapter = {
    if (_log eq null)
      _log = akka.event.Logging(context.system, this)(logSource)
    _log
  }
}