package aecor.data

import cats.data.StateT
import cats.implicits._
import cats.{ FlatMap, Functor, ~> }
import io.aecor.liberator.{ Algebra, FunctorK }

/**
  * `Behavior[M, F]` says that all actions of `M` will cause effect `F`
  * producing a pair consisting of next `Behavior[M, F]` and an `A`
  */
final case class Behavior[M[_[_]], F[_]](actions: M[PairT[F, Behavior[M, F], ?]]) extends AnyVal {
  def mapK[G[_]](f: F ~> G)(implicit M: FunctorK[M], F: Functor[F]): Behavior[M, G] =
    Behavior[M, G] {
      M.mapK[PairT[F, Behavior[M, F], ?], PairT[G, Behavior[M, G], ?]](
        actions,
        new (PairT[F, Behavior[M, F], ?] ~> PairT[G, Behavior[M, G], ?]) {
          override def apply[A](fa: PairT[F, Behavior[M, F], A]): PairT[G, Behavior[M, G], A] =
            f(F.map(fa)(x => ((x._1: Behavior[M, F]).mapK(f), x._2)))
        }
      )
    }
}

object Behavior {

  def roll[F[_]: FlatMap, M[_[_]]](f: F[Behavior[M, F]])(implicit M: Algebra[M]): Behavior[M, F] =
    Behavior[M, F] {
      M.fromFunctionK[PairT[F, Behavior[M, F], ?]] {
        new (M.Out ~> PairT[F, Behavior[M, F], ?]) {
          override def apply[A](op: M.Out[A]): PairT[F, Behavior[M, F], A] =
            f.flatMap(x => M.toFunctionK[PairT[F, Behavior[M, F], ?]](x.actions)(op))
        }
      }
    }

  def fromState[S, M[_[_]], F[_]: FlatMap](state: S, f: M[StateT[F, S, ?]])(
    implicit M: Algebra[M]
  ): Behavior[M, F] = {
    val fk = M.toFunctionK(f)
    Behavior[M, F] {
      M.fromFunctionK[PairT[F, Behavior[M, F], ?]] {
        new (M.Out ~> PairT[F, Behavior[M, F], ?]) {
          override def apply[A](fa: M.Out[A]): PairT[F, Behavior[M, F], A] =
            fk(fa).run(state).map {
              case (next, a) =>
                fromState(next, f) -> a
            }
        }
      }
    }
  }

}
