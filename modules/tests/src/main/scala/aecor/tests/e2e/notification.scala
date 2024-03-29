package aecor.tests.e2e

import aecor.MonadAction
import aecor.data.Folded.syntax._
import aecor.data.{ EventsourcedBehavior, _ }
import aecor.encoding.WireProtocol
import aecor.macros.boopickle.BoopickleWireProtocol
import aecor.tests.e2e.notification.NotificationEvent.{ NotificationCreated, NotificationSent }
import cats.Monad
import cats.tagless.{ Derive, FunctorK }

object notification {
  type NotificationId = String

  trait Notification[F[_]] {
    def create(counterId: CounterId): F[Unit]
    def markAsSent: F[Unit]
  }

  object Notification {
    import boopickle.Default._
    implicit def functorK: FunctorK[Notification] = Derive.functorK
    implicit def wireProtocol: WireProtocol[Notification] = BoopickleWireProtocol.derive
  }

  sealed abstract class NotificationEvent
  object NotificationEvent {
    final case class NotificationCreated(counterId: CounterId) extends NotificationEvent
    final case object NotificationSent extends NotificationEvent
    val tag: EventTag = EventTag("Notification")
  }

  case class NotificationState(sent: Boolean) {
    def applyEvent(e: NotificationEvent): Folded[NotificationState] = e match {
      case NotificationCreated(_) => NotificationState(false).next
      case NotificationSent       => NotificationState(true).next
    }
  }

  def notificationActions[F[_]](implicit
      F: MonadAction[F, NotificationState, NotificationEvent]
  ): Notification[F] = new Notification[F] {
    import F._
    override def create(counterId: CounterId): F[Unit] = append(NotificationCreated(counterId))
    override def markAsSent: F[Unit] = append(NotificationSent)
  }

  def behavior[F[_]: Monad]
      : EventsourcedBehavior[Notification, F, NotificationState, NotificationEvent] =
    EventsourcedBehavior(notificationActions, Fold(NotificationState(false), _.applyEvent(_)))
}
