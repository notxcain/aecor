package aecor.core.aggregate

import akka.actor.ActorRef
import akka.pattern.{ask => askPattern}
import akka.util.Timeout
import cats.~>

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

private final class AggregateRegionRef[Command[_]](shardRegion: ActorRef,
                                                   askTimeout: FiniteDuration)
    extends (Command ~> Future) {

  implicit private val timeout = Timeout(askTimeout)

  @deprecated("0.13.0", "Use FunctionK#apply instead")
  def ask[Response](command: Command[Response]): Future[Response] =
    apply(command)

  override def apply[A](fa: Command[A]): Future[A] = {
    (shardRegion ? fa).asInstanceOf[Future[A]]
  }
}
