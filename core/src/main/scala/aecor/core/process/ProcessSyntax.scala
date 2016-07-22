package aecor.core.process

import aecor.core.aggregate.{AggregateRef, CommandContract, CommandId}
import aecor.core.process.ProcessReaction.{ChangeBehavior, DeliverCommand, _}
import aecor.util.{FunctionBuilder, FunctionBuilderSyntax, generate}

trait ProcessSyntax extends FunctionBuilderSyntax {
  implicit def toChangeBehavior[H, In](f: H)(implicit ev: FunctionBuilder[H, In, ProcessReaction[In]]): In => ProcessReaction[In] = ev(f)

  final def become[In](f: In => ProcessReaction[In]): ProcessReaction[In] = ChangeBehavior(f)

  trait RejectionHandler[A] {
    outer =>
    def map[B](f: A => B): RejectionHandler[B] = new RejectionHandler[B] {
      override def apply[In](handler: (B) => ProcessReaction[In]): ProcessReaction[In] =
        outer.apply(x => handler(f(x)))
    }

    def apply[In](handler: A => ProcessReaction[In]): ProcessReaction[In]
  }

  trait DeliverCommand[R] {

    def handlingRejection: RejectionHandler[R]

    def ignoringRejection[In]: ProcessReaction[In] = handlingRejection(_ => DoNothing)
  }

  implicit class EntityRefOps[Entity](er: AggregateRef[Entity]) {
    final def deliver[Command](command: Command)(implicit contract: CommandContract[Entity, Command]) =
      new DeliverCommand[contract.Rejection] {
        override def handlingRejection: RejectionHandler[contract.Rejection] = new RejectionHandler[contract.Rejection] {
          override def apply[In](handler: (contract.Rejection) => ProcessReaction[In]): ProcessReaction[In] = DeliverCommand(er, command, handler, generate[CommandId])
        }
      }

    final def deliver[Command](commandId: String, command: Command)(implicit contract: CommandContract[Entity, Command]) =
      new DeliverCommand[contract.Rejection] {
        override def handlingRejection: RejectionHandler[contract.Rejection] = new RejectionHandler[contract.Rejection] {
          override def apply[In](handler: (contract.Rejection) => ProcessReaction[In]): ProcessReaction[In] = DeliverCommand(er, command, handler, CommandId(commandId))
        }
      }
  }

  final def doNothing[E]: ProcessReaction[E] = DoNothing

  final def when[A] = new At[A] {
    override def apply[Out](f: (A) => Out): (A) => Out = f
  }

  //  def withContext[E](f: EventContext => ProcessAction[E]): ProcessAction[E] = WithEventContext(f)

  private val _ignore: Any => ProcessReaction[_] = _ => doNothing

  def ignore[E]: Any => ProcessReaction[E] = _ignore.asInstanceOf[Any => ProcessReaction[E]]
}

object ProcessSyntax extends ProcessSyntax
