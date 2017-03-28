package aecor.data

import cats.arrow.FunctionK
import cats.{ FlatMap, Functor, ~> }
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

object Behavior {
  def roll[F[_]: FlatMap, Op[_]](f: F[Behavior[Op, F]]): Behavior[Op, F] =
    Behavior[Op, F](new (Op ~> PairT[F, Behavior[Op, F], ?]) {
      override def apply[A](op: Op[A]): PairT[F, Behavior[Op, F], A] =
        FlatMap[F].flatMap(f)(_.run(op))
    })
}
