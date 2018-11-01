package aecor.tests.e2e

import aecor.MonadAction
import aecor.data._
import aecor.data.Folded.syntax._
import aecor.macros.boopickleWireProtocol
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentEncoder }
import aecor.tests.PersistentEncoderCirce
import aecor.tests.e2e.CounterEvent.{ CounterDecremented, CounterIncremented }
import io.circe.generic.auto._
import cats.implicits._
import boopickle.Default._
import cats.tagless.autoFunctorK
import cats.{ Eq, Monad }

@boopickleWireProtocol
@autoFunctorK
trait Counter[F[_]] {
  def increment: F[Long]
  def decrement: F[Long]
  def value: F[Long]
}

object Counter

final case class CounterId(value: String) extends AnyVal

sealed abstract class CounterEvent extends Product with Serializable
object CounterEvent {
  case object CounterIncremented extends CounterEvent
  case object CounterDecremented extends CounterEvent
  val tag: EventTag = EventTag("Counter")
  implicit val eq: Eq[CounterEvent] = Eq.fromUniversalEquals
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
  def instance[F[_]: Monad]: EventsourcedBehavior[Counter, F, CounterState, CounterEvent] =
    EventsourcedBehavior(
      CounterActions[ActionT[F, CounterState, CounterEvent, ?]],
      CounterState(0),
      _.applyEvent(_)
    )
}

final class CounterActions[F[_]](implicit F: MonadAction[F, CounterState, CounterEvent])
    extends Counter[F] {

  import F._

  override def increment: F[Long] = append(CounterIncremented) >> read.map(_.value)

  override def decrement: F[Long] = append(CounterDecremented) >> read.map(_.value)

  override def value: F[Long] = read.map(_.value)

}

object CounterActions {
  def apply[F[_]](implicit F: MonadAction[F, CounterState, CounterEvent]): Counter[F] =
    new CounterActions[F]
}
