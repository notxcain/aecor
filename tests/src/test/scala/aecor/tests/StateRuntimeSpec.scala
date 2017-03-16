package aecor.tests

import aecor.aggregate.{ Correlation, CorrelationIdF, Folder, StateRuntime }
import aecor.data.Handler
import cats.{ Id, ~> }
import org.scalatest.{ FunSuite, Matchers }

class StateRuntimeSpec extends FunSuite with Matchers {
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

  val singletonRuntime =
    StateRuntime.singleton[CounterOp, CounterState, CounterEvent, Id](behavior)

  val sharedRuntime =
    StateRuntime.shared[CounterOp, CounterState, CounterEvent, Id](behavior, correlation)

  test("singleton runtime should execute all commands against single sequence of events") {
    val program = for {
      _ <- singletonRuntime(Increment("1"))
      _ <- singletonRuntime(Increment("2"))
      x <- singletonRuntime(Decrement("3"))
    } yield x

    val (events, result) = program.run(Vector.empty)

    events should have size 3
    events.head shouldBe CounterIncremented("1")
    events.last shouldBe CounterDecremented("3")
    result shouldBe 1L
  }

  test("shared runtime should execute commands against events identified by correlation function") {
    val program = for {
      _ <- sharedRuntime(Increment("1"))
      _2 <- sharedRuntime(Increment("2"))
      _1 <- sharedRuntime(Decrement("1"))
    } yield (_1, _2)

    val (state, (_1, _2)) =
      program.run(Map.empty)

    state("1") should have size 2
    state("2") should have size 1
    _1 shouldBe 0l
    _2 shouldBe 1L
  }
}
