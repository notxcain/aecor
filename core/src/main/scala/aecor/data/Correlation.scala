package aecor.data

object Correlation {
  def apply[C[_]](f: C[_] => CorrelationId): Correlation[C] = f
}
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
