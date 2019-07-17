package aecor.kafkadistributedprocessing

import aecor.kafkadistributedprocessing.internal.Channel
import cats.effect.IO
import cats.implicits._
import fs2.concurrent.Queue
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._

class ChannelTest extends AnyFunSuite with IOSupport {
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
            w <- watch.start
            c <- call.start
            _ <- close
            _ <- c.join
            _ <- w.cancel
          } yield ()
      }
      .replicateA(100)
      .unsafeRunTimed(10.seconds)
      .get
    assert(true)
  }
}
