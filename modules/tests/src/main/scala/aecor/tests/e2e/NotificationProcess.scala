package aecor.tests.e2e

import aecor.data.EntityEvent
import aecor.tests.e2e.CounterEvent.CounterIncremented
import aecor.tests.e2e.notification.{ Notification, NotificationEvent, NotificationId }
import aecor.util.FunctionBuilder.syntax._
import cats.Monad
import cats.implicits._
import shapeless.{ :+:, CNil, HNil }

object NotificationProcess {
  type Input =
    EntityEvent[CounterId, CounterEvent] :+: EntityEvent[NotificationId, NotificationEvent] :+: CNil

  def apply[F[_]: Monad](counters: CounterId => Counter[F],
                         notifications: NotificationId => Notification[F]): Input => F[Unit] =
    build {
      at[EntityEvent[CounterId, CounterEvent]] {
        case EntityEvent(counterId, _, CounterIncremented) =>
          for {
            value <- counters(counterId).value
            _ <- if (value % 2 == 0) {
                  notifications(s"${counterId.value}-$value").create(counterId)
                } else {
                  ().pure[F]
                }
          } yield ()
        case _ => ().pure[F]
      } ::
        at[EntityEvent[NotificationId, NotificationEvent]] {
        case EntityEvent(nid, _, NotificationEvent.NotificationCreated(_)) =>
          notifications(nid).markAsSent
        case _ =>
          ().pure[F]
      } ::
        HNil
    }

}
