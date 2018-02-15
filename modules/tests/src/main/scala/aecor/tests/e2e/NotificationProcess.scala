package aecor.tests.e2e

import aecor.data.Identified
import aecor.tests.e2e.CounterEvent.CounterIncremented
import aecor.tests.e2e.notification.{ Notification, NotificationEvent, NotificationId }
import aecor.util.FunctionBuilder.syntax._
import cats.Monad
import cats.implicits._
import shapeless.{ :+:, CNil, HNil }

object NotificationProcess {
  type Input =
    Identified[CounterId, CounterEvent] :+: Identified[NotificationId, NotificationEvent] :+: CNil

  def apply[F[_]: Monad](counters: CounterId => Counter[F],
                         notifications: NotificationId => Notification[F]): Input => F[Unit] =
    build {
      at[Identified[CounterId, CounterEvent]] {
        case Identified(counterId, CounterIncremented) =>
          for {
            value <- counters(counterId).value
            _ <- if (value % 2 == 0) {
                  notifications(s"${counterId.value}-$value").createNotification(counterId)
                } else {
                  ().pure[F]
                }
          } yield ()
        case _ => ().pure[F]
      } ::
        at[Identified[NotificationId, NotificationEvent]] {
        case Identified(nid, NotificationEvent.NotificationCreated(_)) =>
          notifications(nid).markAsSent
        case _ =>
          ().pure[F]
      } ::
        HNil
    }

}
