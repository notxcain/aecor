package aecor.tests.e2e

import aecor.data._
import aecor.tests.e2e.notification.NotificationEvent.{ NotificationCreated, NotificationSent }
import aecor.tests.e2e.notification.NotificationOp.{ CreateNotification, MarkAsSent }
import cats.{ Applicative, ~> }
import Folded.syntax._

object notification {
  type NotificationId = String
  sealed abstract class NotificationOp[A] extends Product with Serializable
  object NotificationOp {
    final case class CreateNotification(counterId: CounterId) extends NotificationOp[Unit]
    final case object MarkAsSent extends NotificationOp[Unit]
  }

  sealed trait NotificationEvent
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

  def notificationOpHandler[F[_]: Applicative] =
    new (NotificationOp ~> ActionT[F, NotificationState, NotificationEvent, ?]) {
      override def apply[A](
        fa: NotificationOp[A]
      ): ActionT[F, NotificationState, NotificationEvent, A] =
        fa match {
          case CreateNotification(cid) =>
            ActionT.lift { _ =>
              Vector(NotificationCreated(cid)) -> (())
            }
          case MarkAsSent =>
            ActionT.lift { _ =>
              Vector(NotificationSent) -> (())
            }
        }
    }
  def behavior[F[_]: Applicative]
    : EventsourcedBehavior[F, NotificationOp, NotificationState, NotificationEvent] =
    EventsourcedBehavior(NotificationState(false), notificationOpHandler[F], _.applyEvent(_))
}
