package aecor.experimental

import aecor.data.{ Behavior, PairT }
import cats.data.StateT
import cats.implicits._
import cats.{ Monad, ~> }

object StateBehavior {
  def apply[F[_]: Monad, Op[_], S](state: F[S], f: Op ~> StateT[F, S, ?]): Behavior[F, Op] =
    Behavior[F, Op](new (Op ~> PairT[F, Behavior[F, Op], ?]) {
      override def apply[A](fa: Op[A]): PairT[F, Behavior[F, Op], A] =
        state.flatMap(f(fa).run).map {
          case (next, a) =>
            StateBehavior(next.pure[F], f) -> a
        }
    })
}
