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

  val sharedRuntime =
    StateRuntime.shared[Either[Throwable, ?], CounterOp, CounterState, CounterEvent](
      EventsourcedBehavior(CounterOpHandler[Either[Throwable, ?]], CounterState.folder)
    )

  val correlated
    : String => CounterOp ~> StateT[Either[Throwable, ?], Map[String, Vector[CounterEvent]], ?] =
    StateRuntime.correlate(sharedRuntime)

  def mkProgram[F[_]: Monad](runtime: CounterOp ~> F): F[Long] =
    for {
      _ <- runtime(Increment)
      _ <- runtime(Increment)
      x <- runtime(Decrement)
    } yield x

  test("Shared runtime should execute all commands against shared sequence of events") {
    val program = mkProgram(sharedRuntime)

    val Right((state, result)) = program.run(Vector.empty)

    state shouldBe Vector(CounterIncremented, CounterIncremented, CounterDecremented)
    result shouldBe 1L
  }

  test(
    "Correlated runtime should execute commands against the events identified by a correlation function"
  ) {

    val program = for {
      _ <- correlated("1")(Increment)
      _ <- correlated("2")(Increment)
      x <- correlated("1")(Decrement)
    } yield x

    val Right((state, result)) = program.run(Map.empty)

    state shouldBe Map(
      "1" -> Vector(CounterIncremented, CounterDecremented),
      "2" -> Vector(CounterIncremented)
    )

    result shouldBe 0L
  }
}
