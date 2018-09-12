package aecor.data

import cats.data.StateT
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.~>
import io.aecor.liberator.{Invocation, ReifiedInvocations}

object Behavior {
  def fromState[S, M[_[_]], F[_]: Sync](state: S, f: M[StateT[F, S, ?]])(
    implicit M: ReifiedInvocations[M]
  ): F[M[F]] =
    for {
      ref <- Ref[F].of(state)
    } yield
      M.mapInvocations {
        new (Invocation[M, ?] ~> F) {
          override def apply[A](op: Invocation[M, A]): F[A] =
            op.invoke(f).run(state).flatMap {
              case (next, a) =>
                ref.set(next).as(a)
            }
        }
      }


}
