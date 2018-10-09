package aecor.runtime.queue

import aecor.runtime.queue.Runtime.{ CommandEnvelope, MemberAddress }
import cats.effect.IO
import fs2.concurrent.Queue
import org.scalatest.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import fs2._
class RuntimeSpec extends FunSuite with IOContextShift with IOTimer {
  test("Runtime should work") {
    val program = for {
      runtime <- Runtime.create[IO](MemberAddress("localhost", 9449), 10.seconds, 60.seconds)
      queue <- Queue.unbounded[IO, CommandEnvelope[String]]
      out <- runtime
        .deploy[String, Counter](
        "Counter",
        _ => Counter.inmem[IO],
        queue,
        Stream.emit(queue.dequeue)
      )
        .use { counters =>
          for {
            _ <- counters("foo").increment
            _ <- counters("bar").increment
            _ <- counters("foo").increment
            foo <- counters("foo").value
            bar <- counters("bar").value
          } yield (foo, bar)
        }

    } yield out

    val result = program.unsafeRunSync()
    assert(result == (2 -> 1))
  }
}
