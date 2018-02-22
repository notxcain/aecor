package aecor.tests

import aecor.testkit.StateRuntime
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.{ Counter, CounterBehavior, CounterEvent }
import cats.data.StateT
import cats.implicits._
import org.scalatest.{ FunSuite, Matchers }

class StateRuntimeSpec extends FunSuite with Matchers {

  val counter: Counter[StateT[Either[Throwable, ?], Vector[CounterEvent], ?]] =
    StateRuntime.single(CounterBehavior.instance.lift[Either[Throwable, ?]])

  val counters
    : String => Counter[StateT[Either[Throwable, ?], Map[String, Vector[CounterEvent]], ?]] =
    StateRuntime.route(counter)

  test("Shared runtime should execute all commands against shared sequence of events") {
    val program = for {
      _ <- counter.increment
      _ <- counter.increment
      x <- counter.decrement
    } yield x

    val Right((state, result)) = program.run(Vector.empty)

    state shouldBe Vector(CounterIncremented, CounterIncremented, CounterDecremented)
    result shouldBe 1L
  }

  test(
    "Correlated runtime should execute commands against the events identified by a correlation function"
  ) {

    val program = for {
      _ <- counters("1").increment
      _ <- counters("2").increment
      x <- counters("1").decrement
    } yield x

    val Right((state, result)) = program.run(Map.empty)

    state shouldBe Map(
      "1" -> Vector(CounterIncremented, CounterDecremented),
      "2" -> Vector(CounterIncremented)
    )

    result shouldBe 0L
  }
}
