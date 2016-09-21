package aecor.core.message

import aecor.core.message.Correlation.CorrelationId

object Correlation {
  type CorrelationId = String
  def instance[A](f: A => String): Correlation[A] = new Correlation[A] {
    override def apply(a: A): CorrelationId = f(a)
  }
}

trait Correlation[A] extends (A => CorrelationId) {
  def apply(a: A): CorrelationId
}

