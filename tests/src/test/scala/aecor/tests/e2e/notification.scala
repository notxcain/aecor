package aecor.tests.e2e

import aecor.data._
import aecor.tests.e2e.notification.NotificationEvent.{ NotificationCreated, NotificationSent }
import aecor.tests.e2e.notification.NotificationOp.{ CreateNotification, MarkAsSent }
import cats.implicits._
import cats.{ Applicative, ~> }
import monix.reactive.MulticastStrategy.Behavior

import scala.collection.immutable.Seq

object notification {
  sealed trait NotificationOp[A] {
    def notificationId: String
  }

  object NotificationOp {
    case class CreateNotification(notificationId: String, counterId: String)
        extends NotificationOp[Unit]
    case class MarkAsSent(notificationId: String) extends NotificationOp[Unit]
    val correlation: Correlation[NotificationOp] = Correlation[NotificationOp](_.notificationId)
  }

  sealed trait NotificationEvent
  object NotificationEvent {
    case class NotificationCreated(notificationId: String, counterId: String)
        extends NotificationEvent
    case class NotificationSent(notificationId: String) extends NotificationEvent
    val tag: EventTag[NotificationEvent] = EventTag[NotificationEvent]("Notification")
  }

  case class NotificationState(sent: Boolean)
  object NotificationState {
    def folder[F[_]: Applicative]: Folder[F, NotificationEvent, NotificationState] =
      Folder.curried(NotificationState(false)) {
        case NotificationState(_) => {
          case NotificationCreated(_, _) => NotificationState(false).pure[F]
          case NotificationSent(_) => NotificationState(true).pure[F]
        }
      }
  }

  def notificationOpHandler[F[_]: Applicative] =
    new (NotificationOp ~> Handler[F, NotificationState, Seq[NotificationEvent], ?]) {
      override def apply[A](
        fa: NotificationOp[A]
      ): Handler[F, NotificationState, Seq[NotificationEvent], A] =
        fa match {
          case CreateNotification(nid, cid) =>
            Handler.lift { _ =>
              Vector(NotificationCreated(nid, cid)) -> (())
            }
          case MarkAsSent(id) =>
            Handler.lift { _ =>
              Vector(NotificationSent(id)) -> (())
            }
        }
    }
  def behavior[F[_]: Applicative]
    : EventsourcedBehavior[F, NotificationOp, NotificationState, NotificationEvent] =
    EventsourcedBehavior(notificationOpHandler[F], NotificationState.folder[Folded])
}
