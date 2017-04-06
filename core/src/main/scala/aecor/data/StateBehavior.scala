package aecor.data

import cats.data.StateT
import cats.implicits._
import cats.{ Monad, ~> }

object StateBehavior {
  def apply[F[_]: Monad, Op[_], S](f: Op ~> StateT[F, S, ?], state: F[S]): Behavior[Op, F] =
    Behavior[Op, F](new (Op ~> PairT[F, Behavior[Op, F], ?]) {
      override def apply[A](fa: Op[A]): PairT[F, Behavior[Op, F], A] =
        state.flatMap(f(fa).run).map {
          case (next, a) =>
            StateBehavior(f, next.pure[F]) -> a
        }
    })
}
