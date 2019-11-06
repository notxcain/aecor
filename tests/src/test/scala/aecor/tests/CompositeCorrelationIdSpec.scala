package aecor.tests

import aecor.old.aggregate.CorrelationId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CompositeCorrelationIdSpec extends AnyFlatSpec with Matchers {
  "CorrelationId.composite" should "concatenate provided components with provided separator, escaping separator from components" in {
    CorrelationId.composite("-", "foo", "bar-baz") shouldEqual "foo-bar\\-baz"
  }
}
