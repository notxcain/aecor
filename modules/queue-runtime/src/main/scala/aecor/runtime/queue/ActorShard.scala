package aecor.runtime.queue

import aecor.runtime.queue.Actor.Receive
import cats.effect.implicits._
import cats.effect.{ Concurrent, ContextShift, Timer }
import cats.implicits._
import fs2._

import scala.concurrent.duration.FiniteDuration

private [queue]  object ActorShard {
  sealed abstract class Message[+A] extends Product with Serializable
  object Message {
    final case object Terminate extends Message[Nothing]
    final case class Handle[+A](a: A) extends Message[A]
  }

  def create[F[_], K, A](idleTimeout: FiniteDuration)(
    create: K => F[Actor[F, A]])(
    implicit F: Concurrent[F],
    context: ContextShift[F],
    timer: Timer[F]
  ): F[Actor[F, (K, A)]] =
    Actor.create[F, (K, Message[A])] { self =>
      import Message._
      for {
        actors <- F.delay(scala.collection.mutable.Map.empty[K, Actor[F, A]])
        scheduledTerminations <- F.delay(scala.collection.mutable.Map.empty[K, F[Unit]])
      } yield
        Receive[(K, Message[A])] {
          case (key, Handle(a)) =>
            val getActor: F[Actor[F, A]] = F.delay(actors.get(key)).flatMap {
              case Some(actor) => actor.pure[F]
              case None =>
                create(key)
                  .flatTap { a =>
                    F.delay(actors.update(key, a))
                  }
            }

            val tell = getActor.flatMap(_.send(a))

            val cancelTermination =
              F.suspend(scheduledTerminations.get(key).sequence_) >>
                F.delay(scheduledTerminations - key)

            val scheduleTermination =
              (timer.sleep(idleTimeout) >> self.send(key -> Terminate)).start
                .flatMap(fiber => F.delay(scheduledTerminations.update(key, fiber.cancel)))

            cancelTermination >> tell >> scheduleTermination

          case (key, Terminate) =>
            F.delay(scheduledTerminations - key) >>
              F.suspend(actors.get(key).traverse_(_.terminate)) >>
                F.delay(actors - key).void

        }
    }.map(_.contramap[(K, A)](x => x._1 -> Message.Handle(x._2)))

  def apply[F[_], K, A](
    init: K => F[Actor[F, A]],
    idleTimeout: FiniteDuration
  )(implicit F: Concurrent[F], context: ContextShift[F], timer: Timer[F]): Sink[F, (K, A)] = {
    commands =>
      Stream.force {
        create(idleTimeout)(init).map { ac =>
          commands.evalMap(ac.send)
        }
      }

  }
}
