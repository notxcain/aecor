package aecor.tests.e2e

import aecor.data.Identified
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import cats.implicits._
import cats.Monad

object CounterViewProcess {
  def apply[F[_]: Monad](
    repo: CounterViewRepository[F]
  ): Identified[CounterId, CounterEvent] => F[Unit] = {
    case Identified(id, CounterIncremented) =>
      for {
        state <- repo.getCounterState(id)
        _ <- repo.setCounterState(id, state.getOrElse(0L) + 1L)
      } yield ()
    case Identified(id, CounterDecremented) =>
      for {
        state <- repo.getCounterState(id)
        _ <- repo.setCounterState(id, state.getOrElse(0L) - 1L)
      } yield ()
  }
}
