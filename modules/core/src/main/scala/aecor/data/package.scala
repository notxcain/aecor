package aecor

package object data {

  /**
    * A transformer type representing a `(A, B)` wrapped in `F`
    */
  type PairT[F[_], A, B] = F[(A, B)]

  type ActionT[F[_], S, E, R, A] = next.ActionT[F, S, E, R, A]
  val ActionT: next.ActionT.type = next.ActionT
}
