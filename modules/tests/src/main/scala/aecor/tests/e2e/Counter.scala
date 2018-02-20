package aecor.tests.e2e

import aecor.data.Folded.syntax._
import aecor.data._
import aecor.macros.wireProtocol
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.tests.PersistentEncoderCirce
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import io.circe.generic.auto._

@wireProtocol
trait Counter[F[_]] {
  def increment: F[Long]
  def decrement: F[Long]
  def value: F[Long]
}

object Counter

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

object CounterBehavior {
  def instance: EventsourcedBehavior[Counter, CounterState, CounterEvent] =
    EventsourcedBehavior(CounterActions, CounterState(0), _.applyEvent(_))
}

object CounterActions extends Counter[Action[CounterState, CounterEvent, ?]] {

  override def increment: Action[CounterState, CounterEvent, Long] = Action { x =>
    List(CounterIncremented) -> (x.value + 1)
  }

  override def decrement: Action[CounterState, CounterEvent, Long] = Action { x =>
    List(CounterDecremented) -> (x.value - 1)
  }

  override def value: Action[CounterState, CounterEvent, Long] = Action(x => List.empty -> x.value)

}
