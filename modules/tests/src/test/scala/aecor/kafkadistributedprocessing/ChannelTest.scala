package aecor.kafkadistributedprocessing

import aecor.tests.IOSupport
import cats.effect.IO
import cats.implicits._
import fs2.concurrent.Queue
import org.scalatest.FunSuite
import cats.effect.implicits._
import scala.concurrent.duration._

class ChannelTest extends FunSuite with IOSupport {
  test("Channel#call completes only after completion callback") {
    val out = Channel
      .create[IO]
      .flatMap {
        case Channel(watch, _, call) =>
          Queue.unbounded[IO, String].flatMap { queue =>
            for {
              fiber <- watch.flatMap { callback =>
                        queue.enqueue1("before callback") >> callback
                      }.start
              _ <- queue.enqueue1("before call") >> call >> queue.enqueue1("after call")
              _ <- fiber.join
              out <- queue.dequeue.take(3).compile.toList
            } yield out
          }
      }
      .unsafeRunTimed(1.seconds)
      .get
    assert(out == List("before call", "before callback", "after call"))
  }

  test("Channel#call does not wait for completion callback if channel is closed") {

    Channel
      .create[IO]
      .flatMap {
        case Channel(watch, close, call) =>
          for {
            f <- watch.start
            _ <- call.race(close)
            _ <- f.cancel
          } yield ()
      }
      .replicateA(100)
      .unsafeRunTimed(10.seconds)
      .get
    assert(true)
  }
}
