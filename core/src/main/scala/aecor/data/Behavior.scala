package aecor.data

import cats.data.StateT
import cats.implicits._
import cats.{ Applicative, FlatMap, Functor, ~> }

/**
  * `Behavior[Op, F]` says that each operation `Op[A]` will cause effect `F`
  * producing a pair consisting of next `Behavior[Op, F]` and an `A`
  */
final case class Behavior[F[_], Op[_]](run: Op ~> PairT[F, Behavior[F, Op], ?]) extends AnyVal {
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
      f.flatMap(_.run(op))
    }

  type o[F[_], G[_]] = {
    type X[A] = F[G[A]]
  }

  def flattenK[F[_]: FlatMap]: (F o F)#X ~> F =
    new ((F o F)#X ~> F) {
      override def apply[A](fa: F[F[A]]) = fa.flatten
    }

  def wrapRoll[F[_]: Applicative, G[_]: Functor, Op[_]](
    fb: F[Behavior[G, Op]]
  ): Behavior[(F o G)#X, Op] =
    Behavior[(F o G)#X, Op] {
      new (Op ~> PairT[(F o G)#X, Behavior[(F o G)#X, Op], ?]) {
        override def apply[A](op: Op[A]): F[G[(Behavior[(F o G)#X, Op], A)]] =
          fb.map { bg =>
            val gbg: G[(Behavior[G, Op], A)] = bg.run(op)
            gbg.map {
              case (nbg, a) =>
                (wrapRoll(nbg.pure[F]), a)
            }
          }
      }
    }

  def fromState[F[_]: FlatMap, Op[_], S](zero: S, f: Op ~> StateT[F, S, ?]): Behavior[F, Op] =
    Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { fa =>
      f(fa).run(zero).map {
        case (next, a) =>
          fromState(next, f) -> a
      }
    }
}
