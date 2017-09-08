package aecor.tests.e2e

import aecor.data._
import aecor.tests.e2e.notification.NotificationEvent.{ NotificationCreated, NotificationSent }
import aecor.tests.e2e.notification.NotificationOp.{ CreateNotification, MarkAsSent }
import cats.implicits._
import cats.{ Applicative, ~> }

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

  case class NotificationState(sent: Boolean)
  object NotificationState {
    def folder[F[_]: Applicative]: Folder[F, NotificationEvent, NotificationState] =
      Folder.curried(NotificationState(false)) {
        case NotificationState(_) => {
          case NotificationCreated(_) => NotificationState(false).pure[F]
          case NotificationSent       => NotificationState(true).pure[F]
        }
      }
  }

  def notificationOpHandler[F[_]: Applicative] =
    new (NotificationOp ~> Handler[F, NotificationState, NotificationEvent, ?]) {
      override def apply[A](
        fa: NotificationOp[A]
      ): Handler[F, NotificationState, NotificationEvent, A] =
        fa match {
          case CreateNotification(cid) =>
            Handler.lift { _ =>
              Vector(NotificationCreated(cid)) -> (())
            }
          case MarkAsSent =>
            Handler.lift { _ =>
              Vector(NotificationSent) -> (())
            }
        }
    }
  def behavior[F[_]: Applicative]
    : EventsourcedBehavior[F, NotificationOp, NotificationState, NotificationEvent] =
    EventsourcedBehavior(notificationOpHandler[F], NotificationState.folder[Folded])
}
