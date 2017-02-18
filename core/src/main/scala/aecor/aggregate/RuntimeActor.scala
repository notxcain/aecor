package aecor.aggregate

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import aecor.aggregate.Async.ops._
import akka.actor.{ Actor, ActorLogging, Stash, Status }
import akka.pattern._
import cats.implicits._
import cats.{ Functor, ~> }

import scala.concurrent.{ ExecutionContext, Future }

final case class Behavior[Op[_], F[_]](f: Op ~> λ[a => F[(Behavior[Op, F], a)]]) extends AnyVal {
  def apply[A](opa: Op[A]): F[(Behavior[Op, F], A)] = f(opa)
}

trait Async[F[_]] {
  def run[A](fa: F[A])(executionContext: ExecutionContext): Future[A]
}

object Async {
  def apply[F[_]](implicit instance: Async[F]): Async[F] = instance
  object ops {
    implicit class AsyncOps[F[_], A](val self: F[A]) extends AnyVal {
      def run(implicit F: Async[F], executionContext: ExecutionContext): Future[A] =
        F.run(self)(executionContext)
    }
  }
}

final class RuntimeActor[Op[_], F[_]: Async: Functor](identity: Identity,
                                                      loadBehavior: String => F[Behavior[Op, F]])
    extends Actor
    with Stash
    with ActorLogging {

  import context._

  private final case class Init(behavior: Behavior[Op, F])
  private final case class Result(behavior: Behavior[Op, F], reply: Any)

  private val effectiveIdentity: String = identity match {
    case Identity.Provided(value) => value
    case Identity.FromPathName =>
      URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())
  }

  loadBehavior(effectiveIdentity).map(Init).run.pipeTo(self)

  override def receive: Receive = loading

  private def loading: Receive = {
    case Init(behavior) =>
      become(active(behavior))
      unstashAll()
    case failure @ Status.Failure(cause) =>
      log.error(cause, s"[$effectiveIdentity] Loading failed")
      unstashAll()
      become {
        case _ =>
          sender() ! failure
          throw cause
      }
    case _ => stash()
  }

  private def active(behavior: Behavior[Op, F]): Receive = {
    case op =>
      behavior(op.asInstanceOf[Op[Any]]).map(x => Result(x._1, x._2)).run.pipeTo(self)(sender)
      become {
        case Result(newBehavior, reply) =>
          sender() ! reply
          become(active(newBehavior))
          unstashAll()
        case failure @ Status.Failure(cause) =>
          log.error(cause, s"[$effectiveIdentity] Error while processing operation [$op]")
          sender() ! failure
          throw cause
        case _ =>
          stash()
      }
  }
}
