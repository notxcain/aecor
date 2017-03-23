package aecor.aggregate.runtime

import cats.arrow.FunctionK
import cats.data.StateT
import cats.implicits._
import cats.{ Applicative, Functor, ~> }

object behavior {

  /**
    * A transformer type representing a `(A, B)` wrapped in `F`
    */
  type PairT[F[_], A, B] = F[(A, B)]

  /**
    * `Behavior[Op, F]` says that each operation `Op[A]` will cause effect `F`
    * producing a pair consisting of next `Behavior[Op, F]` and an `A`
    */
  final case class Behavior[Op[_], F[_]](run: Op ~> PairT[F, Behavior[Op, F], ?]) {
    def mapK[G[_]: Functor](f: F ~> G): Behavior[Op, G] = Behavior[Op, G] {
      def mk[A](op: Op[A]): PairT[G, Behavior[Op, G], A] =
        f(run(op)).map {
          case (b, a) =>
            (b.mapK(f), a)
        }
      FunctionK.lift(mk _)
    }
  }

  def stateRuntime[Op[_], F[_]: Applicative](
    behavior: Behavior[Op, F]
  ): Op ~> StateT[F, Behavior[Op, F], ?] =
    Lambda[Op ~> StateT[F, Behavior[Op, F], ?]] { op =>
      StateT(_.run(op))
    }
}
