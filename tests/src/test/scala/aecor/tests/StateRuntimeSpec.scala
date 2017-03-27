package aecor.tests

import aecor.aggregate.{ Correlation, Folder, StateRuntime }
import aecor.data.Handler
import cats.implicits._
import cats.{ Applicative, Id, Monad, ~> }
import org.scalatest.{ FunSuite, Matchers }

import scala.collection.immutable.Seq

class StateRuntimeSpec extends FunSuite with Matchers {
  sealed trait CounterOp[A] {
    def id: String
  }
  case class Increment(id: String) extends CounterOp[Long]
  case class Decrement(id: String) extends CounterOp[Long]

  val correlation = Correlation[CounterOp](_.id)

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
  def behavior[F[_]: Applicative]: CounterOp ~> Handler[F, CounterState, Seq[CounterEvent], ?] =
    Lambda[CounterOp ~> Handler[F, CounterState, Seq[CounterEvent], ?]] {
      case Increment(id) =>
        Handler.lift { x =>
          Vector(CounterIncremented(id)) -> (x.value + 1)
        }
      case Decrement(id) =>
        Handler.lift { x =>
          Vector(CounterDecremented(id)) -> (x.value - 1)
        }
    }

  val sharedRuntime =
    StateRuntime.shared[Id, CounterOp, CounterState, CounterEvent](behavior)

  val correlatedRuntime =
    StateRuntime.correlate(sharedRuntime, correlation)

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
