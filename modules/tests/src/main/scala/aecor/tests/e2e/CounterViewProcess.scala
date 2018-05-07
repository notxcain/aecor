package aecor.tests.e2e

import aecor.data.EntityEvent
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import cats.Monad
import cats.implicits._

object CounterViewProcess {
  def apply[F[_]: Monad](
    repo: CounterViewRepository[F]
  ): EntityEvent[CounterId, CounterEvent] => F[Unit] = {
    case EntityEvent(id, _, CounterIncremented) =>
      for {
        state <- repo.getCounterState(id).map(_.getOrElse(0L))
        _ <- repo.setCounterState(id, state + 1L)
      } yield ()
    case EntityEvent(id, _, CounterDecremented) =>
      for {
        state <- repo.getCounterState(id).map(_.getOrElse(0L))
        _ <- repo.setCounterState(id, state - 1L)
      } yield ()
  }
}
