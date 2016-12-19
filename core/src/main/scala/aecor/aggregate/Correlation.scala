package aecor.aggregate

import aecor.aggregate.Correlation.CorrelationIdF
import cats.~>

object Correlation {
  type CorrelationId = String
  type CorrelationIdF[A] = CorrelationId
}

trait Correlation[C[_]] extends (C ~> CorrelationIdF)
