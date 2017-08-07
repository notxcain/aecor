package aecor.tests.e2e
import aecor.data._
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.tests.PersistentEncoderCirce
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.CounterOp.{ Decrement, GetValue, Increment }
import cats.implicits._
import cats.{ Applicative, ~> }
import io.circe.generic.auto._

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
  val tag: EventTag = EventTag("Counter")
  implicit def encoder: PersistentEncoder[CounterEvent] =
    PersistentEncoderCirce.circePersistentEncoder[CounterEvent]
  implicit def decoder: PersistentDecoder[CounterEvent] =
    PersistentEncoderCirce.circePersistentDecoder[CounterEvent]
}

case class CounterState(value: Long)
object CounterState {
  def folder[F[_]: Applicative]: Folder[F, CounterEvent, CounterState] =
    Folder.curried(CounterState(0)) {
      case CounterState(x) => {
        case CounterIncremented(_) => CounterState(x + 1).pure[F]
        case CounterDecremented(_) => CounterState(x - 1).pure[F]
      }
    }
}

object CounterOpHandler {
  def apply[F[_]: Applicative]: CounterOp ~> Handler[F, CounterState, CounterEvent, ?] =
    new CounterOpHandler[F]

  def behavior[F[_]: Applicative]: EventsourcedBehavior[F, CounterOp, CounterState, CounterEvent] =
    EventsourcedBehavior(CounterOpHandler[F], CounterState.folder[Folded])
}

class CounterOpHandler[F[_]: Applicative]
    extends (CounterOp ~> Handler[F, CounterState, CounterEvent, ?]) {
  override def apply[A](fa: CounterOp[A]): Handler[F, CounterState, CounterEvent, A] =
    fa match {
      case Increment(id) =>
        Handler.lift { x =>
          Seq(CounterIncremented(id)) -> (x.value + 1)
        }
      case Decrement(id) =>
        Handler.lift { x =>
          Seq(CounterDecremented(id)) -> (x.value - 1)
        }
      case GetValue(id) =>
        Handler.lift(x => Seq.empty -> x.value)
    }
}
