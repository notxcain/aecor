package aecor.tests

import aecor.data.{ EventTag, Tagging }
import org.scalacheck.Prop.forAll
import org.scalacheck.{ Gen, Properties }

class TaggingTest extends Properties("Tagging") {
  property("Const Tagging") = {
    val tagging = Tagging.const[Int](EventTag("foo"))
    forAll { x: Int =>
      tagging.tag(x) == Set(EventTag("foo"))
    }
  }

  property("Partitioned Tagging") = forAll(Gen.posNum[Int]) { partitionCount =>
    val tagging = Tagging.partitioned[Int](partitionCount)(EventTag(""))

    forAll { x: Int =>
      tagging.tags.contains(tagging.tag(x).head)
    }

    tagging.tags.size == partitionCount
  }
}
