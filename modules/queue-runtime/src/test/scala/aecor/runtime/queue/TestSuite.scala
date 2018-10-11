package aecor.runtime.queue
import aecor.runtime.queue.TestSuite.TestTimeout
import cats.effect.IO
import org.scalactic.source
import org.scalatest.{AsyncFunSuite, Tag, compatible}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait TestSuite extends AsyncFunSuite with IOContextShift with IOTimer {

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global

  protected implicit val testTimeout: TestTimeout = TestTimeout(10.seconds)

  final protected def test(testName: String, testTags: Tag*)(testFun: IO[compatible.Assertion])(implicit pos: source.Position, timeout: TestTimeout): Unit =
    super.test(testName, testTags: _*)(testFun.timeout(timeout.value).unsafeToFuture())

  final protected def ignore(testName: String, testTags: Tag*)(testFun: IO[compatible.Assertion])(implicit pos: source.Position): Unit =
    super.ignore(testName, testTags: _*)(testFun.unsafeToFuture())
}

object TestSuite {
  final case class TestTimeout(value: FiniteDuration) extends AnyVal
}
