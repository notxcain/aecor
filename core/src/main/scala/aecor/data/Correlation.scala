package aecor.data

object Correlation {
  def apply[C[_]](f: C[_] => CorrelationId): Correlation[C] = f
}
object CorrelationId {
  def composite(separator: String,
                firstComponent: String,
                otherComponents: String*): CorrelationId = {
    val replacement = s"\\$separator"
    val builder = new StringBuilder(firstComponent.replace(separator, replacement))
    otherComponents.foreach { component =>
      builder.append(separator)
      builder.append(component.replace(separator, replacement))
    }
    builder.result()
  }
}
