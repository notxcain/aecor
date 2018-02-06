package aecor.tests.e2e
import aecor.data._
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.tests.PersistentEncoderCirce
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.CounterOp.{ Decrement, GetValue, Increment }
import cats.{ Applicative, ~> }
import io.circe.generic.auto._

import scala.collection.immutable.Seq
import Folded.syntax._

sealed abstract class CounterOp[A] extends Product with Serializable
object CounterOp {
  case object Increment extends CounterOp[Long]
  case object Decrement extends CounterOp[Long]
  case object GetValue extends CounterOp[Long]
}

final case class CounterId(value: String) extends AnyVal

sealed trait CounterEvent
object CounterEvent {
  case object CounterIncremented extends CounterEvent
  case object CounterDecremented extends CounterEvent
  val tag: EventTag = EventTag("Counter")
  implicit def encoder: PersistentEncoder[CounterEvent] =
    PersistentEncoderCirce.circePersistentEncoder[CounterEvent]
  implicit def decoder: PersistentDecoder[CounterEvent] =
    PersistentEncoderCirce.circePersistentDecoder[CounterEvent]
}

case class CounterState(value: Long) {
  def applyEvent(e: CounterEvent): Folded[CounterState] = e match {
    case CounterIncremented => CounterState(value + 1).next
    case CounterDecremented => CounterState(value - 1).next
  }
}

object CounterOpHandler {
  def apply[F[_]: Applicative]: CounterOp ~> ActionT[F, CounterState, CounterEvent, ?] =
    new CounterOpHandler[F]
}

object CounterBehavior {
  def apply[F[_]: Applicative]: EventsourcedBehaviorT[F, CounterOp, CounterState, CounterEvent] =
    EventsourcedBehaviorT(CounterState(0), CounterOpHandler[F], _.applyEvent(_))
}

class CounterOpHandler[F[_]: Applicative]
    extends (CounterOp ~> ActionT[F, CounterState, CounterEvent, ?]) {
  private val lift = ActionT.liftK[F, CounterState, CounterEvent]
  override def apply[A](fa: CounterOp[A]): ActionT[F, CounterState, CounterEvent, A] =
    fa match {
      case Increment =>
        lift {
          Action { x: CounterState =>
            Seq(CounterIncremented) -> (x.value + 1)
          }
        }
      case Decrement =>
        lift {
          Action { x =>
            Seq(CounterDecremented) -> (x.value - 1)
          }
        }
      case GetValue =>
        lift {
          Action(x => Seq.empty -> x.value)
        }
    }
}
