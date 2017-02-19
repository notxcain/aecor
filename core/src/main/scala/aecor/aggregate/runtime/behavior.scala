package aecor.aggregate.runtime

import cats.~>

object behavior {
  type Tuple2T[F[_], A, B] = F[(A, B)]

  final case class Fix[F[_]](unFix: F[Fix[F]]) extends AnyVal

  type BehaviorF[Op[_], F[_], A] = Op ~> Tuple2T[F, A, ?]

  type Behavior[Op[_], F[_]] = Fix[BehaviorF[Op, F, ?]]

  object Behavior {
    def apply[Op[_], F[_]](f: Op ~> Tuple2T[F, Behavior[Op, F], ?]): Behavior[Op, F] =
      Fix[BehaviorF[Op, F, ?]](f)
  }

}
