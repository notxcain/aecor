package aecor.data

import scala.annotation.tailrec
import scala.util.Try

object Composer {
  final class WithSeparator(separator: Char) {
    private val escapeChar = '\\'

    def apply(firstComponent: String, secondComponent: String, otherComponents: String*): String =
      apply(firstComponent :: secondComponent :: otherComponents.toList)

    def apply(components: List[String]): String = {
      val builder = new java.lang.StringBuilder()
      var idx = 0
      components.foreach { component =>
        if (idx > 0) builder.append(separator)
        component.foreach { char =>
          if (char == separator || char == escapeChar) {
            builder.append(escapeChar)
          }
          builder.append(char)
        }
        idx = idx + 1
      }
      builder.toString
    }

    def unapply(value: String): Option[List[String]] =
      Some {
        var acc = List.empty[String]
        val component = new java.lang.StringBuilder(36)
        var escaped = false
        value.foreach { x =>
          if (escaped) {
            component.append(x)
            escaped = false
          } else if (x == escapeChar) {
            escaped = true
          } else if (x == separator) {
            acc = component.toString :: acc
            component.setLength(0)
          } else {
            component.append(x)
          }
        }
        acc = component.toString :: acc
        acc.reverse
      }
  }

  object WithSeparator {
    def apply(separator: Char): WithSeparator = new WithSeparator(separator)
  }

  final class WithLengthHint(lengthSeparator: Char) {
    private val lengthSeparatorString = String.valueOf(lengthSeparator)

    def apply(firstComponent: String, secondComponent: String, otherComponents: String*): String =
      apply(firstComponent :: secondComponent :: otherComponents.toList)

    def apply(components: List[String]): String = {
      val builder = new java.lang.StringBuilder
      components.foreach { x =>
        builder.append(x.length)
        builder.append(lengthSeparator)
        builder.append(x)
      }
      builder.toString
    }

    @tailrec
    private def decodeLoop(elements: Vector[String], string: String): Vector[String] =
      if (string.isEmpty) elements
      else {
        val firstSep = string.indexOf(lengthSeparatorString)
        val (sizeString, tail) = (string.take(firstSep), string.drop(firstSep + 1))
        val size = sizeString.toInt
        val (value, remainder) = (tail.take(size), tail.drop(size))
        decodeLoop(elements :+ value, remainder)
      }

    def unapply(string: String): Option[List[String]] =
      Try(decodeLoop(Vector.empty, string).toList).toOption
  }

  object WithLengthHint {
    def apply(lengthSeparator: Char): WithLengthHint = new WithLengthHint(lengthSeparator)
  }

}
