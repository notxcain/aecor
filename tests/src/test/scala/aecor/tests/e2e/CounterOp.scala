package aecor.tests.e2e
import aecor.aggregate.{ Correlation, Folder }
import aecor.data.{ EventTag, Handler }
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.CounterOp.{ Decrement, GetValue, Increment }
import cats.implicits._
import cats.{ Applicative, ~> }

import scala.collection.immutable.Seq

sealed trait CounterOp[A] {
  def id: String
}

object CounterOp {
  case class Increment(id: String) extends CounterOp[Long]
  case class Decrement(id: String) extends CounterOp[Long]
  case class GetValue(id: String) extends CounterOp[Long]
  val correlation: Correlation[CounterOp] = Correlation[CounterOp](_.id)
}

sealed trait CounterEvent
object CounterEvent {
  case class CounterIncremented(id: String) extends CounterEvent
  case class CounterDecremented(id: String) extends CounterEvent
  val tag: EventTag[CounterEvent] = EventTag[CounterEvent]("Counter")
}

case class CounterState(value: Long)
object CounterState {
  implicit def folder[F[_]: Applicative]: Folder[F, CounterEvent, CounterState] =
    Folder.instance(CounterState(0)) {
      case CounterState(x) => {
        case CounterIncremented(_) => CounterState(x + 1).pure[F]
        case CounterDecremented(_) => CounterState(x - 1).pure[F]
      }
    }
}

object CounterOpHandler extends (CounterOp ~> Handler[CounterState, Seq[CounterEvent], ?]) {
  override def apply[A](fa: CounterOp[A]): Handler[CounterState, Seq[CounterEvent], A] =
    fa match {
      case Increment(id) =>
        Handler { x =>
          Vector(CounterIncremented(id)) -> (x.value + 1)
        }
      case Decrement(id) =>
        Handler { x =>
          Vector(CounterDecremented(id)) -> (x.value - 1)
        }
      case GetValue(id) =>
        Handler(x => Vector.empty -> x.value)
    }
}
