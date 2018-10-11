package aecor.runtime.queue

import aecor.runtime.queue.Runtime.{CommandEnvelope, EntityName}
import cats.effect.IO

import scala.concurrent.duration._
class RuntimeSpec extends TestSuite {
  test("Runtime should work") {
    for {
      runtime <- Runtime.create[IO, Unit](10.seconds, 60.seconds, ClientServer.local)
      queue <- PartitionedQueue.local[IO, EntityName, CommandEnvelope[Unit, String]]
      result <- runtime
        .run[String, Counter](
        EntityName("Counter"),
        _ => Counter.inmem[IO],
        queue
      )
        .use { counters =>
          for {
            _ <- counters("foo").increment
            _ <- counters("bar").increment
            _ <- counters("baz").increment
            _ <- counters("foo").increment
            _ <- counters("baz").decrement
            foo <- counters("foo").value
            bar <- counters("bar").value
            baz <- counters("baz").value
          } yield (foo, bar, baz)
        }

    } yield assert(result == ((2, 1, 0)))
  }
}
