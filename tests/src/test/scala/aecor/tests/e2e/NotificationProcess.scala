package aecor.tests.e2e

import aecor.data.Identified
import aecor.tests.e2e.CounterEvent.CounterIncremented
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationId, NotificationOp }
import cats.{ Monad, ~> }
import shapeless.{ :+:, CNil, HNil }
import aecor.util.FunctionBuilder.syntax._
import cats.implicits._

object NotificationProcess {
  type Input =
    Identified[CounterId, CounterEvent] :+: Identified[NotificationId, NotificationEvent] :+: CNil

  def apply[F[_]: Monad](counters: CounterId => CounterOp ~> F,
                         notifications: NotificationId => NotificationOp ~> F): Input => F[Unit] =
    build {
      at[Identified[CounterId, CounterEvent]] {
        case Identified(counterId, CounterIncremented) =>
          for {
            value <- counters(counterId)(CounterOp.GetValue)
            _ <- if (value % 2 == 0) {
                  notifications(s"$counterId-$value")(NotificationOp.CreateNotification(counterId))
                } else {
                  ().pure[F]
                }
          } yield ()
        case _ => ().pure[F]
      } ::
        at[Identified[NotificationId, NotificationEvent]] {
        case Identified(nid, NotificationEvent.NotificationCreated(_)) =>
          notifications(nid)(NotificationOp.MarkAsSent)
        case _ =>
          ().pure[F]
      } ::
        HNil
    }

}
