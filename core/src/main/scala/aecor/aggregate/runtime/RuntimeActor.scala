package aecor.aggregate.runtime

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

import aecor.aggregate.runtime.RuntimeActor.InstanceIdentity
import aecor.aggregate.runtime.behavior.Behavior
import akka.actor.{ Actor, ActorLogging, Props, ReceiveTimeout, Stash, Status }
import akka.cluster.sharding.ShardRegion
import cats.Functor
import akka.pattern.pipe
import Async.ops._
import cats.implicits._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }

trait Async[F[_]] {
  def run[A](fa: F[A])(executionContext: ExecutionContext): Future[A]
  def capture[A](future: => Future[A]): F[A]
}

object Async extends AsyncInstances {
  def apply[F[_]](implicit instance: Async[F]): Async[F] = instance
  object ops {
    implicit class AsyncOps[F[_], A](val self: F[A]) extends AnyVal {
      def run(implicit F: Async[F], executionContext: ExecutionContext): Future[A] =
        F.run(self)(executionContext)
    }
  }
}
sealed trait AsyncInstances {
  implicit val futureAsyncInstance: Async[Future] = new Async[Future] {
    override def run[A](fa: Future[A])(executionContext: ExecutionContext): Future[A] = fa
    override def capture[A](future: => Future[A]): Future[A] = future
  }
}

object RuntimeActor {
  def props[F[_]: Async: Functor, Op[_]](entityName: String,
                                         loadBehavior: InstanceIdentity => F[Behavior[Op, F]],
                                         idleTimeout: FiniteDuration): Props =
    Props(new RuntimeActor(entityName, loadBehavior, idleTimeout))

  case object Stop

  final case class InstanceIdentity(entityId: String, instanceId: UUID) {
    def modifyEntityId(f: String => String): InstanceIdentity = copy(entityId = f(entityId))
  }
}

final class RuntimeActor[Op[_], F[_]: Async: Functor](
  entityName: String,
  loadBehavior: InstanceIdentity => F[Behavior[Op, F]],
  idleTimeout: FiniteDuration
) extends Actor
    with Stash
    with ActorLogging {

  import context._

  private final case class Init(behavior: Behavior[Op, F])
  private final case class Result(behavior: Behavior[Op, F], reply: Any)

  private val entityId: String =
    s"$entityName-${URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())}"

  private val instanceId = UUID.randomUUID

  log.info("[{}] Starting...", entityId)
  loadBehavior(InstanceIdentity(entityId, instanceId)).map(Init).run.pipeTo(self)

  override def receive: Receive = loading

  private def loading: Receive = {
    case Init(behavior) =>
      become(active(behavior))
      unstashAll()
      setIdleTimeout()
    case failure @ Status.Failure(cause) =>
      unstashAll()
      become {
        case _ =>
          sender() ! failure
          throw cause
      }
    case _ => stash()
  }

  private def receivePassivationMessages: Receive = {
    case ReceiveTimeout =>
      passivate()
    case RuntimeActor.Stop =>
      context.stop(self)
  }

  private def active(behavior: Behavior[Op, F]): Receive = receivePassivationMessages.orElse {
    case op =>
      behavior
        .unFix(op.asInstanceOf[Op[Any]])
        .map(x => Result(x._1, x._2))
        .run
        .pipeTo(self)(sender)
      become {
        case Result(newBehavior, reply) =>
          sender() ! reply
          become(active(newBehavior))
          unstashAll()
        case failure @ Status.Failure(cause) =>
          sender() ! failure
          throw cause
        case _ =>
          stash()
      }
  }

  private def passivate(): Unit = {
    log.debug("[{}] Passivating...", entityId)
    context.parent ! ShardRegion.Passivate(RuntimeActor.Stop)
  }

  private def setIdleTimeout(): Unit = {
    log.debug("[{}] Setting idle timeout to [{}]", entityId, idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }
}
