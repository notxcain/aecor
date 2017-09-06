package aecor.data

import cats.data.StateT
import cats.implicits._
import cats.{ FlatMap, Functor, ~> }

/**
  * `Behavior[Op, F]` says that each operation `Op[A]` will cause effect `F`
  * producing a pair consisting of next `Behavior[Op, F]` and an `A`
  */
final case class Behavior[F[_], Op[_]](run: Op ~> PairT[F, Behavior[F, Op], ?]) {
  def mapK[G[_]: Functor](f: F ~> G): Behavior[G, Op] =
    Lambda[Op ~> PairT[G, Behavior[G, Op], ?]] { op =>
      f(run(op)).map {
        case (b, a) =>
          (b.mapK(f), a)
      }
    }
}

object Behavior {

  implicit def fromFunctionK[F[_], Op[_]](f: Op ~> PairT[F, Behavior[F, Op], ?]): Behavior[F, Op] =
    Behavior[F, Op](f)

  def roll[F[_]: FlatMap, Op[_]](f: F[Behavior[F, Op]]): Behavior[F, Op] =
    Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { op =>
      FlatMap[F].flatMap(f)(_.run(op))
    }

  def fromState[F[_]: FlatMap, Op[_], S](zero: S, f: Op ~> StateT[F, S, ?]): Behavior[F, Op] =
    Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { fa =>
      f(fa).run(zero).map {
        case (next, a) =>
          fromState(next, f) -> a
      }
    }

  def correlated[F[_], Op[_]](f: Op[_] => Behavior[F, Op]): Behavior[F, Op] =
    Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { op =>
      f(op).run(op)
    }
}
