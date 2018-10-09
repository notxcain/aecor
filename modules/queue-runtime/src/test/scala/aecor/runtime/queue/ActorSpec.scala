package aecor.runtime.queue
import aecor.runtime.queue.Actor.Receive
import cats.effect.IO
import cats.effect.concurrent.Ref
import fs2.concurrent.Queue
import org.scalatest.FunSuite
import cats.implicits._

class ActorSpec extends FunSuite with IOContextShift {

  test("Actor should receive sent messages") {
    val program = for {
      queue <- Queue.unbounded[IO, Int]
      actor <- Actor.create[IO, Int](_ => IO.pure(queue.enqueue1))
      _ <- actor.send(1)
      _ <- actor.send(2)
      out <- queue.dequeue.take(2).compile.toVector
    } yield out
    val result = program.unsafeRunSync()
    assert(result == Vector(1, 2))
  }

  test("Actor should receive messages sent to itself") {
    val program = for {
      queue <- Queue.unbounded[IO, Int]
      actor <- Actor.create[IO, Int](ctx => IO.pure(x => queue.enqueue1(x) >> ctx.send(x + 1)))
      _ <- actor.send(1)
      out <- queue.dequeue.take(2).compile.toVector
    } yield out
    val result = program.unsafeRunSync()
    assert(result == Vector(1, 2))
  }

  test("Actor should terminate and complete subsequent tell with error") {
    val program = for {
      actor <- Actor.create[IO, Int](_ => IO.pure(_ => ().pure[IO]))
      _ <- actor.send(1)
      _ <- actor.terminate
      out <- actor.send(2).attempt
    } yield out
    val result = program.unsafeRunSync()
    assert(result.left.get.isInstanceOf[IllegalStateException])
  }

  test("Actor should restart after receive error ") {
    val program = for {
      queue <- Queue.unbounded[IO, Int]
      actor <- Actor.create[IO, Unit] { ctx =>
        Ref[IO].of(0).map { ref =>
          Receive[Unit] { _ =>
            ref.get.flatMap { c =>
              if (c == 1) {
                IO.raiseError(new RuntimeException("Terminate"))
              } else {
                queue.enqueue1(c) >> ref.set(c + 1)
              }
            }
          }
        }
      }
      _ <- actor.send(())
      _ <- actor.send(())
      _ <- actor.send(())
      out <- queue.dequeue.take(2).compile.toVector
    } yield out
    val result = program.unsafeRunSync()
    assert(result == Vector(0, 0))
  }



}
