package aecor.tests

import aecor.data.{ EventTag, Tagging }
import org.scalacheck.Gen
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
    forAll(Gen.posNum[Int]) { partitionCount =>
      val tagging = Tagging.partitioned[Int](partitionCount)(EventTag(""))

      forAll { x: Int =>
        tagging.tags should contain(tagging.tag(x).head)
      }

      tagging.tags.size shouldBe partitionCount
    }
  }
}
