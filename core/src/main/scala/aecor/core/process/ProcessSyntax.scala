package aecor.core.process

import aecor.core.entity.{CommandContract, EntityRef}
import aecor.core.process.ProcessReaction.{ChangeBehavior, DeliverCommand}
import aecor.util.{FunctionBuilder, FunctionBuilderSyntax}
import ProcessReaction._

trait ProcessSyntax extends FunctionBuilderSyntax {
  implicit def toChangeBehavior[H, In](f: H)(implicit ev: FunctionBuilder[H, In, ProcessReaction[In]]): In => ProcessReaction[In] = ev(f)

  final def become[In](f: In => ProcessReaction[In]): ProcessReaction[In] = ChangeBehavior(f)

  trait DeliverCommand[R] {
    trait HandlingRejection[A] { outer =>
      def map[B](f: A => B): HandlingRejection[B] = new HandlingRejection[B] {
        override def apply[In](handler: (B) => ProcessReaction[In]): ProcessReaction[In] = outer.apply(x => handler(f(x)))
      }
      def apply[In](handler: A => ProcessReaction[In]): ProcessReaction[In]
    }
    def handlingRejection: HandlingRejection[R]
    def ignoringRejection[In]: ProcessReaction[In] = handlingRejection(_ => DoNothing)
  }

  implicit class EntityRefOps[Entity](er: EntityRef[Entity]) {
    final def deliver[Command](command: Command)(implicit contract: CommandContract[Entity, Command]) =
      new DeliverCommand[contract.Rejection] {
        override def handlingRejection: HandlingRejection[contract.Rejection] = new HandlingRejection[contract.Rejection] {
          override def apply[In](handler: (contract.Rejection) => ProcessReaction[In]): ProcessReaction[In] = DeliverCommand(er, command, handler)
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
