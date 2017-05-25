package aecor.data

import cats.arrow.FunctionK
import cats.data.StateT
import cats.implicits._
import cats.{ FlatMap, Functor, ~> }

/**
  * `Behavior[Op, F]` says that each operation `Op[A]` will cause effect `F`
  * producing a pair consisting of next `Behavior[Op, F]` and an `A`
  */
final case class Behavior[F[_], Op[_]](run: Op ~> PairT[F, Behavior[F, Op], ?]) {
  def mapK[G[_]: Functor](f: F ~> G): Behavior[G, Op] = Behavior[G, Op] {
    def mk[A](op: Op[A]): PairT[G, Behavior[G, Op], A] =
      f(run(op)).map {
        case (b, a) =>
          (b.mapK(f), a)
      }
    FunctionK.lift(mk _)
  }
}

object Behavior {
  def roll[F[_]: FlatMap, Op[_]](f: F[Behavior[F, Op]]): Behavior[F, Op] =
    Behavior[F, Op](new (Op ~> PairT[F, Behavior[F, Op], ?]) {
      override def apply[A](op: Op[A]): PairT[F, Behavior[F, Op], A] =
        FlatMap[F].flatMap(f)(_.run(op))
    })

  def fromState[F[_]: FlatMap, Op[_], S](zero: S, f: Op ~> StateT[F, S, ?]): Behavior[F, Op] =
    Behavior[F, Op](new (Op ~> PairT[F, Behavior[F, Op], ?]) {
      override def apply[A](fa: Op[A]): PairT[F, Behavior[F, Op], A] =
        f(fa).run(zero).map {
          case (next, a) =>
            fromState(next, f) -> a
        }
    })

  def correlated[F[_], Op[_]](f: Op[_] => Behavior[F, Op]): Behavior[F, Op] =
    Behavior(Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { op =>
      f(op).run(op)
    })
}
