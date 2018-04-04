package aecor.data

import io.aecor.liberator.Invocation
import cats.data.StateT
import cats.implicits._
import cats.{ FlatMap, Functor, ~> }
import io.aecor.liberator.{ FunctorK, ReifiedInvocations }

/**
  * `Behavior[M, F]` says that all actions of `M` will cause an effect `F`
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

  def roll[F[_]: FlatMap, M[_[_]]](
    f: F[Behavior[M, F]]
  )(implicit M: ReifiedInvocations[M]): Behavior[M, F] =
    Behavior[M, F] {
      M.mapInvocations[PairT[F, Behavior[M, F], ?]] {
        new (Invocation[M, ?] ~> PairT[F, Behavior[M, F], ?]) {
          override def apply[A](op: Invocation[M, A]): PairT[F, Behavior[M, F], A] =
            f.flatMap(x => op.invoke[PairT[F, Behavior[M, F], ?]](x.actions))
        }
      }
    }

  def fromState[S, M[_[_]], F[_]: FlatMap](state: S, f: M[StateT[F, S, ?]])(
    implicit M: ReifiedInvocations[M]
  ): Behavior[M, F] =
    Behavior[M, F] {
      M.mapInvocations[PairT[F, Behavior[M, F], ?]] {
        new (Invocation[M, ?] ~> PairT[F, Behavior[M, F], ?]) {
          override def apply[A](op: Invocation[M, A]): PairT[F, Behavior[M, F], A] =
            op.invoke(f).run(state).map {
              case (next, a) =>
                fromState(next, f) -> a
            }
        }
      }
    }

}
