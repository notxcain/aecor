package aecor.tests.e2e

import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.CounterOp.Increment
import cats.{ Monad, ~> }
import cats.implicits._

object CounterViewProcess {
  def apply[F[_]: Monad](repo: CounterViewRepository[F],
                         counter: CounterOp ~> F): CounterEvent => F[Unit] = {
    case CounterIncremented(id) =>
      for {
        state <- repo.getCounterState(id)
        _ <- if (id == "1") {
              counter(Increment("2"))
            } else {
              ().pure[F]
            }
        _ <- repo.setCounterState(id, state.getOrElse(0L) + 1L)
      } yield ()
    case CounterDecremented(id) =>
      for {
        state <- repo.getCounterState(id)
        _ <- repo.setCounterState(id, state.getOrElse(0L) - 1L)
      } yield ()
  }
}
