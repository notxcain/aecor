package aecor.tests.e2e

import aecor.aggregate.{ Correlation, Folder }
import aecor.data.{ EventTag, Handler }
import aecor.tests.e2e.notification.NotificationEvent.{ NotificationCreated, NotificationSent }
import aecor.tests.e2e.notification.NotificationOp.{ CreateNotification, MarkAsSent }
import cats.implicits._
import cats.{ Applicative, ~> }

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
    implicit def folder[F[_]: Applicative]: Folder[F, NotificationEvent, NotificationState] =
      Folder.instance(NotificationState(false)) {
        case NotificationState(_) => {
          case NotificationCreated(_, _) => NotificationState(false).pure[F]
          case NotificationSent(_) => NotificationState(true).pure[F]
        }
      }
  }

  object NotificationOpHandler
      extends (NotificationOp ~> Handler[NotificationState, Seq[NotificationEvent], ?]) {
    override def apply[A](
      fa: NotificationOp[A]
    ): Handler[NotificationState, Seq[NotificationEvent], A] =
      fa match {
        case CreateNotification(nid, cid) =>
          Handler { _ =>
            Vector(NotificationCreated(nid, cid)) -> (())
          }
        case MarkAsSent(id) =>
          Handler { _ =>
            Vector(NotificationSent(id)) -> (())
          }
      }
  }
}
