package aecor.runtime.queue
import cats.effect.IO
import cats.effect.concurrent.Deferred
import cats.implicits._
import fs2.concurrent.Queue

import scala.concurrent.duration._
class ActorShardSpec extends TestSuite {
  test("It should create separate actor for each key") {
    for {
      deferredA <- Deferred[IO, Int]
      deferredB <- Deferred[IO, Int]
      shard <- ActorShard.create[IO, String, Int](5.seconds) {
        case "A" =>
          Actor.create { _ =>
            IO.pure(deferredA.complete)
          }
        case "B" =>
          Actor.create { _ =>
            IO.pure(deferredB.complete)
          }
      }
      _ <- shard.send(("A", 1))
      _ <- shard.send(("B", 2))
      a <- deferredA.get
      b <- deferredB.get
    } yield assert(a == 1 && b == 2)
  }

  test("It should terminate actor after idle timeout") {
    for {
      queue <- Queue.unbounded[IO, Boolean]
      shard <- ActorShard.create[IO, String, Int](1.second) {
        _ =>
          Actor.void[IO, Int].flatTap { actor =>
            actor.watchTermination.flatMap(_ => queue.enqueue1(true)).start
          }
      }
      _ <- shard.send(("B", 2))
      a <- queue.dequeue1
      _ <- shard.send(("B", 2))
      b <- queue.dequeue1
    } yield assert(a && b)
  }
}
