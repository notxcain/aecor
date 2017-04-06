package aecor

package object data {
  type CorrelationId = String
  type Correlation[C[_]] = (C[_] => CorrelationId)

  /**
    * A transformer type representing a `(A, B)` wrapped in `F`
    */
  type PairT[F[_], A, B] = F[(A, B)]
}
