package aecor.runtime.queue

import cats.effect.IO

import scala.concurrent.duration._
class RuntimeSpec extends TestSuite {
  test("Runtime should work") {
    for {
      runtime <- Runtime.create[IO](10.seconds, 60.seconds)
      result <- runtime
                 .run[String, Unit, Counter](
                   _ => Counter.inmem[IO],
                   ClientServer.local,
                   PartitionedQueue.local(1)(_ => 0)
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
