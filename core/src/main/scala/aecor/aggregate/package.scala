package aecor

import cats.~>

package object aggregate {
  type CorrelationId = String
  type CorrelationIdF[A] = CorrelationId
  type Correlation[C[_]] = (C ~> CorrelationIdF)
  object CorrelationId {
    def composite(sep: String, firstComponent: String, otherComponents: String*): CorrelationId = {
      val replacement = s"\\$sep"
      val builder = new StringBuilder(firstComponent.replace(sep, replacement))
      otherComponents.foreach { component =>
        builder.append(sep)
        builder.append(component.replace(sep, replacement))
      }
      builder.result()
    }
  }
}
