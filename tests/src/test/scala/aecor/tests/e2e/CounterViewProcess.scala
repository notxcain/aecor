package aecor.tests.e2e

import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import cats.implicits._
import cats.{ Monad, ~> }

object CounterViewProcess {
  def apply[F[_]: Monad](repo: CounterViewRepository[F],
                         counter: CounterOp ~> F): CounterEvent => F[Unit] = {
    case CounterIncremented(id) =>
      for {
        state <- repo.getCounterState(id)
        _ <- repo.setCounterState(id, state.getOrElse(0L) + 1L)
      } yield ()
    case CounterDecremented(id) =>
      for {
        state <- repo.getCounterState(id)
        _ <- repo.setCounterState(id, state.getOrElse(0L) - 1L)
      } yield ()
  }
}
