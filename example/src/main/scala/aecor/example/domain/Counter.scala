package aecor.example.domain
import aecor.core.entity._
object Counter {

  case class Command[+Rejection](counterId: String, intent: Intent[Rejection])
  sealed trait Intent[+Rejection]
  case object CreateCounter extends Intent[CreateCounterRejection]
  case object IncrementCounter extends Intent[IncrementCounterRejection]
  case object DecrementCounter extends Intent[DecrementCounterRejection]

  sealed trait CreateCounterRejection
  sealed trait IncrementCounterRejection
  sealed trait DecrementCounterRejection
  case object CounterDoesNotExist extends IncrementCounterRejection with DecrementCounterRejection
  case object CounterAlreadyExists extends CreateCounterRejection

  case class Event(counterId: String, fact: Fact)
  sealed trait Fact
  case object CounterCreated extends Fact
  case object CounterIncremented extends Fact
  case object CounterDecremented extends Fact
  case object CounterReset extends Fact

  sealed trait CounterState
  case object Initial extends CounterState
  case class Working(counterId: String, value: Long, version: Long) extends CounterState {
    def nextVersion(f: Working => Working): Working = f(copy(version = version + 1))
  }

  import CommandHandlerResult._

  implicit def commandContract[R]: CommandContract.Aux[Counter, Command[R], R] = CommandContract.instance
  implicit def handler[Rejection]: CommandHandler.Aux[Counter, CounterState, Command[Rejection], Event, Rejection] =
    CommandHandler.instance {
      case Initial => command => command.intent match {
        case CreateCounter => accept(Event(command.counterId, CounterCreated))
        case IncrementCounter => reject(CounterDoesNotExist)
        case DecrementCounter => reject(CounterDoesNotExist)
      }
      case Working(counterId: String, value: Long, version: Long) => _.intent match {
        case CreateCounter => reject(CounterAlreadyExists)
        case IncrementCounter => accept(Event(counterId, CounterIncremented))
        case DecrementCounter => accept(Event(counterId, CounterDecremented))
      }
    }

  implicit def projector: EventProjector[Counter, CounterState, Event] =
    EventProjector.instance {
      case Initial => e => e.fact match {
        case CounterCreated => Working(e.counterId, 0, 0)
        case _ => throw new IllegalStateException("Unexpected event")
      }
      case r: Working => _.fact match {
        case CounterIncremented => r.nextVersion(_.copy(value = r.value + 1))
        case CounterDecremented => r.nextVersion(_.copy(value = r.value - 1))
        case _ => r
      }
    }
}

sealed trait Counter
