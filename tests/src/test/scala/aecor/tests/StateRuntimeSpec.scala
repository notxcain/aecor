package aecor.tests

import aecor.old.aggregate.{Correlation, CorrelationIdF, Folder, StateRuntime}
import aecor.old.data.Handler
import cats.{Id, Monad, ~>}
import cats.implicits._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StateRuntimeSpec extends AnyFunSuite with Matchers {
  sealed trait CounterOp[A] {
    def id: String
  }
  case class Increment(id: String) extends CounterOp[Long]
  case class Decrement(id: String) extends CounterOp[Long]

  val correlation = new (Correlation[CounterOp]) {
    override def apply[A](fa: CounterOp[A]): CorrelationIdF[A] = fa.id
  }

  sealed trait CounterEvent
  case class CounterIncremented(id: String) extends CounterEvent
  case class CounterDecremented(id: String) extends CounterEvent
  case class CounterState(value: Long)
  object CounterState {
    implicit val folder: Folder[Id, CounterEvent, CounterState] =
      Folder.instance(CounterState(0)) {
        case CounterState(x) => {
          case CounterIncremented(_) => CounterState(x + 1)
          case CounterDecremented(_) => CounterState(x - 1)
        }
      }
  }
  val behavior: CounterOp ~> Handler[CounterState, CounterEvent, ?] =
    Lambda[CounterOp ~> Handler[CounterState, CounterEvent, ?]] {
      case Increment(id) =>
        Handler { x =>
          Vector(CounterIncremented(id)) -> (x.value + 1)
        }
      case Decrement(id) =>
        Handler { x =>
          Vector(CounterDecremented(id)) -> (x.value - 1)
        }
    }

  val sharedRuntime =
    StateRuntime.shared[CounterOp, CounterState, CounterEvent, Id](behavior)

  val correlatedRuntime =
    StateRuntime.correlated[CounterOp, CounterState, CounterEvent, Id](behavior, correlation)

  def mkProgram[F[_]: Monad](runtime: CounterOp ~> F): F[Long] =
    for {
      _ <- runtime(Increment("1"))
      _ <- runtime(Increment("2"))
      x <- runtime(Decrement("1"))
    } yield x

  test("Shared runtime should execute all commands against shared sequence of events") {
    val program = mkProgram(sharedRuntime)

    val (state, result) = program.run(Vector.empty)

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

    val (state, result) = program.run(Map.empty)

    state shouldBe Map(
      "1" -> Vector(CounterIncremented("1"), CounterDecremented("1")),
      "2" -> Vector(CounterIncremented("2"))
    )

    result shouldBe 0L
  }
}
