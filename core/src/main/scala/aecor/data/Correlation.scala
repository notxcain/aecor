package aecor.data

object CorrelationId {
  def composite(separator: String,
                firstComponent: String,
                secondComponent: String,
                otherComponents: String*): String = {
    val replacement = s"\\$separator"
    (firstComponent +: secondComponent +: otherComponents)
      .map(_.replace(separator, replacement))
      .mkString(separator)
  }
}
