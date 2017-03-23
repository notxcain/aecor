package aecor.tests.e2e

import aecor.tests.e2e.CounterEvent.CounterIncremented
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationOp }
import cats.{ Monad, ~> }
import shapeless.{ :+:, CNil, HNil }
import aecor.util.FunctionBuilder.syntax._
import cats.implicits._

object NotificationProcess {
  type Input = CounterEvent :+: NotificationEvent :+: CNil
  def apply[F[_]: Monad](counterFacade: CounterOp ~> F,
                         notificationFacade: NotificationOp ~> F): Input => F[Unit] =
    build {
      at[CounterEvent] {
        case CounterIncremented(counterId) =>
          for {
            value <- counterFacade(CounterOp.GetValue(counterId))
            _ <- if (value % 2 == 0) {
                  notificationFacade(
                    NotificationOp.CreateNotification(s"$counterId-$value", counterId)
                  )
                } else {
                  ().pure[F]
                }
          } yield ()
        case _ => ().pure[F]
      } ::
        at[NotificationEvent] {
        case NotificationEvent.NotificationCreated(nid, _) =>
          notificationFacade(NotificationOp.MarkAsSent(nid))
        case _ =>
          ().pure[F]
      } ::
        HNil
    }

}
