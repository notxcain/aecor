package aecor.tests.e2e
import aecor.data._
import aecor.encoding.{ KeyDecoder, KeyEncoder }
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.tests.PersistentEncoderCirce
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import aecor.tests.e2e.CounterOp.{ Decrement, GetValue, Increment }
import cats.implicits._
import cats.{ Applicative, ~> }
import io.circe.generic.auto._

import scala.collection.immutable.Seq

sealed abstract class CounterOp[A] extends Product with Serializable
object CounterOp {
  case object Increment extends CounterOp[Long]
  case object Decrement extends CounterOp[Long]
  case object GetValue extends CounterOp[Long]
}

final case class CounterId(value: String) extends AnyVal

object CounterId {
  implicit val keyEncoder: KeyEncoder[CounterId] =
    KeyEncoder[String].contramap(_.value)
  implicit val keyDecoder: KeyDecoder[CounterId] = KeyDecoder[String].map(CounterId(_))
}

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

case class CounterState(value: Long)
object CounterState {
  def folder[F[_]: Applicative]: Folder[F, CounterEvent, CounterState] =
    Folder.curried(CounterState(0)) {
      case CounterState(x) => {
        case CounterIncremented => CounterState(x + 1).pure[F]
        case CounterDecremented => CounterState(x - 1).pure[F]
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
      case Increment =>
        Handler.lift { x =>
          Seq(CounterIncremented) -> (x.value + 1)
        }
      case Decrement =>
        Handler.lift { x =>
          Seq(CounterDecremented) -> (x.value - 1)
        }
      case GetValue =>
        Handler.lift(x => Seq.empty -> x.value)
    }
}
