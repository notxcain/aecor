package aecor.tests

import aecor.data.{ EventTag, Tagging }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{ FunSuite, Matchers }

class TaggingTest extends FunSuite with Matchers with GeneratorDrivenPropertyChecks {
  test("Const Tagging") {
    val tagging = Tagging.const[Int](EventTag("foo"))
    forAll { x: Int =>
      tagging.tag(x) shouldBe Set(EventTag("foo"))
    }
  }

  test("Partitioned Tagging") {
    val tagging = Tagging.partitioned[Int](10)(EventTag(""))

    forAll { x: Int =>
      tagging.tags should contain(tagging.tag(x).head)
    }

    tagging.tags.size shouldBe 10
  }
}
