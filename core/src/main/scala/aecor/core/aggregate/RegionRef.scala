package aecor.core.aggregate

import akka.actor.ActorRef
import akka.pattern.{ask => askPattern}
import akka.util.Timeout
import cats.~>

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

private final class RegionRef[C[_]](shardRegion: ActorRef,
                                    askTimeout: FiniteDuration)
    extends (C ~> Future) {

  implicit private val timeout = Timeout(askTimeout)

  @deprecated("0.13.0", "Use FunctionK#apply instead")
  def ask[Response](command: C[Response]): Future[Response] =
    apply(command)

  override def apply[A](fa: C[A]): Future[A] = {
    (shardRegion ? fa).asInstanceOf[Future[A]]
  }
}
