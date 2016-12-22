package aecor

import cats.~>

package object aggregate {
  type CorrelationId = String
  type CorrelationIdF[A] = CorrelationId
  type Correlation[C[_]] = (C ~> CorrelationIdF)
}
