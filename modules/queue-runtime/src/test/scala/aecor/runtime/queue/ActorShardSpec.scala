package aecor.runtime.queue
import cats.effect.IO
import cats.effect.concurrent.Deferred
import org.scalatest.FunSuite
import cats.implicits._

import scala.concurrent.duration._
class ActorShardSpec extends FunSuite with IOContextShift with IOTimer {
  test("It should create separate actor for each key") {
    val program = for {
      queueA <- Deferred[IO, Int]
      queueB <- Deferred[IO, Int]
      shard <- ActorShard.create[IO, String, Int](5.seconds) {
        case "A" =>
          Actor.create { ctx =>
            IO.pure(queueA.complete)
          }
        case "B" =>
          Actor.create { ctx =>
            IO.pure(queueB.complete)
          }
      }
      _ <- shard.send(("A", 1))
      _ <- shard.send(("B", 2))
      a <- queueA.get
      b <- queueB.get
      _ <- shard.terminate
    } yield (a, b)
    val (a, b) = program.unsafeRunSync()
    assert(a == 1)
    assert(b == 2)
  }

  test("It should terminate actor after idle timeout") {
    val program = for {
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
    } yield a
    val result = program.unsafeRunSync()
    assert(result)
  }

  test("It should buffer actor messages during passivation") {
    assert(false)
  }
}
