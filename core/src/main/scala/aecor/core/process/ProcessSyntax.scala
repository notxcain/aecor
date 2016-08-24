package aecor.core.process

import aecor.core.aggregate.AggregateResponse
import aecor.core.aggregate.AggregateResponse.{Accepted, Rejected}
import aecor.util.{FunctionBuilder, FunctionBuilderSyntax}
import cats.Monad

trait ProcessSyntax extends FunctionBuilderSyntax {

  final def when[A] = at

  implicit class futureResultOps[R, F[_]](f: F[AggregateResponse[R]])(implicit F: Monad[F]) {
    def ignoreRejection[S](s: S): F[S] = F.map(f)(_ => s)
    def handleResult[S](whenAccepted: => S)(whenRejected: R => F[S]) =
      F.flatMap(f) {
        case Accepted => F.pure(whenAccepted)
        case Rejected(rejection) => whenRejected(rejection)
      }
  }

  def handleF[State, In, Out, H](state: State, in: In)(f: State => H)(implicit H: FunctionBuilder[H, In, Out]): Out = H(f(state))(in)
}

object ProcessSyntax extends ProcessSyntax
