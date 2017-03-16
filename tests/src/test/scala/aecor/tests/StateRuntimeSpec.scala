package aecor.tests

import aecor.aggregate.{ Correlation, CorrelationIdF, Folder, StateRuntime }
import aecor.data.Handler
import cats.{ Id, Monad, ~> }
import org.scalatest.{ FunSuite, Matchers }
import cats.implicits._

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

  def mkProgram[F[_]: Monad](runtime: CounterOp ~> F): F[Long] =
    for {
      _ <- runtime(Increment("1"))
      _ <- runtime(Increment("2"))
      x <- runtime(Decrement("1"))
    } yield x

  test("singleton runtime should execute all commands against single sequence of events") {
    val program = mkProgram(singletonRuntime)

    val (events, result) = program.run(Vector.empty)

    events should have size 3
    events shouldBe Vector(
      CounterIncremented("1"),
      CounterIncremented("2"),
      CounterDecremented("1")
    )
    result shouldBe 1L
  }

  test("shared runtime should execute commands against events identified by correlation function") {
    val program = mkProgram(sharedRuntime)

    val (state, result) =
      program.run(Map.empty)

    state("1") shouldBe Vector(CounterIncremented("1"), CounterDecremented("1"))
    state("2") shouldBe Vector(CounterIncremented("2"))
    result shouldBe 0L
  }
}
