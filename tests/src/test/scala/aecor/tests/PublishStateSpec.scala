package aecor.tests

import aecor.core.entity.PublishState
import org.scalatest.{FunSuite, Matchers, WordSpecLike}

class PublishStateSpec extends FunSuite with Matchers {
  test("Empty publish state should have no outstanding element") {
    val empty = PublishState.empty[String](Some(1))
    empty.outstandingEvent.shouldBe(empty)
    empty.
  }
  test("")
}
