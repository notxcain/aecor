package aecor.tests

import aecor.data.EventsourcedBehavior
import aecor.experimental.StateRuntime
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.CounterOp.{ Decrement, Increment }
import aecor.tests.e2e.{ CounterEvent, CounterOp, CounterOpHandler, CounterState }
import cats.implicits._
import cats.{ Id, Monad, ~> }
import org.scalatest.{ FunSuite, Matchers }

class StateRuntimeSpec extends FunSuite with Matchers {

  val sharedRuntime =
    StateRuntime.shared[Either[Throwable, ?], CounterOp, CounterState, CounterEvent](
      EventsourcedBehavior(CounterOpHandler[Either[Throwable, ?]], CounterState.folder)
    )

  val correlatedRuntime =
    StateRuntime.correlate(sharedRuntime, CounterOp.correlation)

  def mkProgram[F[_]: Monad](runtime: CounterOp ~> F): F[Long] =
    for {
      _ <- runtime(Increment("1"))
      _ <- runtime(Increment("2"))
      x <- runtime(Decrement("1"))
    } yield x

  test("Shared runtime should execute all commands against shared sequence of events") {
    val program = mkProgram(sharedRuntime)

    val Right((state, result)) = program.run(Vector.empty)

    state shouldBe Vector(
      CounterIncremented("1"),
      CounterIncremented("2"),
      CounterDecremented("1")
    )
    result shouldBe 1L
  }

  test(
    "Correlated runtime should execute commands against the events identified by a correlation function"
  ) {
    val program = mkProgram(correlatedRuntime)

    val Right((state, result)) = program.run(Map.empty)

    state shouldBe Map(
      "1" -> Vector(CounterIncremented("1"), CounterDecremented("1")),
      "2" -> Vector(CounterIncremented("2"))
    )

    result shouldBe 0L
  }
}
