package aecor.aggregate.runtime

import cats.~>

object behavior {

  /**
    * A transformer type representing a Tuple2 wrapped in [F]
    */
  type Tuple2T[F[_], A, B] = F[(A, B)]

  /**
    * `Behavior[Op, F]` says that each operation `Op[A]` will cause effect `F`
    * producing a tuple consisting of next `Behavior[Op, F]` and an `A`
    */
  final case class Behavior[Op[_], F[_]](run: Op ~> Tuple2T[F, Behavior[Op, F], ?])
}
