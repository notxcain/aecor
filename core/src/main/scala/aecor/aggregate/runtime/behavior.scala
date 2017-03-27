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
  type BehaviorT[Op[_], F[_], A] = PairT[F, Behavior[Op, F], A]

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
}
