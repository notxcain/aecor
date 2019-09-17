package aecor.tests

import aecor.data.Composer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ComposerSpec extends AnyFlatSpec with Matchers {
  val components = List("fo\\-o-", "bar---baz\\", "weww,--12321d''xqw\\xqw---")
  "Composer.WithSeparator" should "concatenate provided components" in {
    val separatedEncoder = Composer.WithSeparator('-')
    separatedEncoder.unapply(separatedEncoder(components)) should contain(components)
  }
  "Composer.WithLengthHint" should "concatenate provided components" in {
    val lengthHintedEncoder = Composer.WithLengthHint('=')
    lengthHintedEncoder.unapply(lengthHintedEncoder(components)) should contain(components)
  }
}
