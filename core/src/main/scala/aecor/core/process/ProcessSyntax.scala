package aecor.core.process

import aecor.core.aggregate.Result
import aecor.core.aggregate.Result.{Accepted, Rejected}
import aecor.util.{FunctionBuilder, FunctionBuilderSyntax}

import scala.concurrent.{ExecutionContext, Future}

trait ProcessSyntax extends FunctionBuilderSyntax {

  final def when[A] = new At[A] {
    override def apply[Out](f: (A) => Out): (A) => Out = f
  }

  implicit class futureResultOps[R](f: Future[Result[R]])(implicit ec: ExecutionContext) {
    def ignoreRejection[S](s: S): Future[S] = f.map(_ => s)
    def handleResult[S](whenAccepted: => S)(whenRejected: R => Future[S]) =
      f.flatMap {
        case Accepted => Future.successful(whenAccepted)
        case Rejected(rejection) => whenRejected(rejection)
      }
  }

  def handleF[State, In, Out, H](state: State, in: In)(f: State => H)(implicit H: FunctionBuilder[H, In, Out]): Out = H(f(state))(in)
}

object ProcessSyntax extends ProcessSyntax
