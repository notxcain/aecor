package aecor.aggregate.runtime

import cats.~>

object behavior {
  type Tuple2T[F[_], A, B] = F[(A, B)]
  final case class Behavior[Op[_], F[_]](run: Op ~> Tuple2T[F, Behavior[Op, F], ?])
}
