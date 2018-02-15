package aecor.tests.e2e

import aecor.data.Folded.syntax._
import aecor.data._
import aecor.tests.e2e.notification.NotificationEvent.{ NotificationCreated, NotificationSent }
import io.aecor.liberator.macros.{ algebra, functorK }

object notification {
  type NotificationId = String

  @algebra
  @functorK
  trait Notification[F[_]] {
    def createNotification(counterId: CounterId): F[Unit]
    def markAsSent: F[Unit]
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

  def notificationActions = new Notification[Action[NotificationState, NotificationEvent, ?]] {
    override def createNotification(
      counterId: CounterId
    ): Action[NotificationState, NotificationEvent, Unit] =
      Action { _ =>
        List(NotificationCreated(counterId)) -> (())
      }

    override def markAsSent: Action[NotificationState, NotificationEvent, Unit] =
      Action { _ =>
        List(NotificationSent) -> (())
      }
  }

  def behavior: EventsourcedBehavior[Notification, NotificationState, NotificationEvent] =
    EventsourcedBehavior(notificationActions, NotificationState(false), _.applyEvent(_))
}
