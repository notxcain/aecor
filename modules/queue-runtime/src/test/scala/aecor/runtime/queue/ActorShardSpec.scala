package aecor.runtime.queue
import cats.effect.IO
import cats.effect.concurrent.Deferred
import cats.implicits._

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
      _ <- shard.terminate
    } yield assert(a == 1 && b == 2)
  }

  test("It should terminate actor after idle timeout") {
    for {
      terminated <- Deferred[IO, Boolean]
      shard <- ActorShard.create[IO, String, Int](1.second) {
        _ =>
          Actor.ignore[IO, Int].flatTap { actor =>
            actor.watchTermination.flatMap(_ => terminated.complete(true)).start
          }
      }
      _ <- shard.send(("B", 2))
      a <- terminated.get
      _ <- shard.terminate
    } yield assert(a)
  }
}
