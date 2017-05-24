package aecor.data

object Correlation {
  def apply[C[_]](f: C[_] => CorrelationId): Correlation[C] = f
}
object CorrelationId {
  def composite(separator: String,
                firstComponent: String,
                secondComponent: String,
                otherComponents: String*): CorrelationId = {
    val replacement = s"\\$separator"
    (firstComponent +: secondComponent +: otherComponents)
      .map(_.replace(separator, replacement))
      .mkString(separator)
  }
}
