package aecor.core.aggregate

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{ask => askPattern}
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

final class AggregateRegionRef[Command[_]](system: ActorSystem, shardRegion: ActorRef, askTimeout: FiniteDuration) {

  implicit private val timeout = Timeout(askTimeout)

  def ask[Response](command: Command[Response]): Future[Response] = {
    import system.dispatcher
    (shardRegion ? command).map(_.asInstanceOf[Response])
  }
}

