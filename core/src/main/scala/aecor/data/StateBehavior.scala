package aecor.data

import cats.data.StateT
import cats.implicits._
import cats.{ Monad, ~> }

object StateBehavior {
  def apply[F[_]: Monad, Op[_], S](f: Op ~> StateT[F, S, ?], state: F[S]): Behavior[F, Op] =
    Behavior[F, Op](new (Op ~> PairT[F, Behavior[F, Op], ?]) {
      override def apply[A](fa: Op[A]): PairT[F, Behavior[F, Op], A] =
        state.flatMap(f(fa).run).map {
          case (next, a) =>
            StateBehavior(f, next.pure[F]) -> a
        }
    })
}
