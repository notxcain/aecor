package aecor.tests

import aecor.data.EventsourcedBehavior
import aecor.testkit.StateRuntime
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.CounterOp.{ Decrement, Increment }
import aecor.tests.e2e.{ CounterEvent, CounterOp, CounterOpHandler, CounterState }
import cats.data.StateT
import cats.implicits._
import cats.{ Monad, ~> }
import org.scalatest.{ FunSuite, Matchers }

class StateRuntimeSpec extends FunSuite with Matchers {

  val counter =
    StateRuntime.unit[Either[Throwable, ?], CounterOp, CounterState, CounterEvent](
      EventsourcedBehavior(CounterOpHandler[Either[Throwable, ?]], CounterState.folder)
    )

  val counters
    : String => CounterOp ~> StateT[Either[Throwable, ?], Map[String, Vector[CounterEvent]], ?] =
    StateRuntime.route(counter)

  def mkProgram[F[_]: Monad](runtime: CounterOp ~> F): F[Long] =
    for {
      _ <- runtime(Increment)
      _ <- runtime(Increment)
      x <- runtime(Decrement)
    } yield x

  test("Shared runtime should execute all commands against shared sequence of events") {
    val program = mkProgram(counter)

    val Right((state, result)) = program.run(Vector.empty)

    state shouldBe Vector(CounterIncremented, CounterIncremented, CounterDecremented)
    result shouldBe 1L
  }

  test(
    "Correlated runtime should execute commands against the events identified by a correlation function"
  ) {

    val program = for {
      _ <- counters("1")(Increment)
      _ <- counters("2")(Increment)
      x <- counters("1")(Decrement)
    } yield x

    val Right((state, result)) = program.run(Map.empty)

    state shouldBe Map(
      "1" -> Vector(CounterIncremented, CounterDecremented),
      "2" -> Vector(CounterIncremented)
    )

    result shouldBe 0L
  }
}
