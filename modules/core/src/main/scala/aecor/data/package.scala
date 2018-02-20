package aecor

package object data {

  /**
    * A transformer type representing a `(A, B)` wrapped in `F`
    */
  type PairT[F[_], A, B] = F[(A, B)]

  @deprecated("Use Action", "0.16.0")
  type Handler[S, E, A] = Action[S, E, A]
}
