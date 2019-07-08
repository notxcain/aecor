package aecor.tests

import aecor.old.aggregate.CorrelationId
import org.scalatest.{ FlatSpec, Matchers }

class CompositeCorrelationIdSpec extends FlatSpec with Matchers {
  "CorrelationId.composite" should "concatenate provided components with provided separator, escaping separator from components" in {
    CorrelationId.composite("-", "foo", "bar-baz") shouldEqual "foo-bar\\-baz"
  }
}
