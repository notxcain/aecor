package aecor.data

import cats.arrow.FunctionK
import cats.{ Functor, ~> }
import cats.implicits._

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
