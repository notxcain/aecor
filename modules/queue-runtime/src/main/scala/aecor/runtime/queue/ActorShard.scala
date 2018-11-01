package aecor.runtime.queue

import aecor.runtime.queue.Actor.Receive
import cats.effect.{ Concurrent, Resource, Timer }
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

private[queue] object ActorShard {
  sealed abstract class Message[+A] extends Product with Serializable
  object Message {
    final case object TerminateWorker extends Message[Nothing]
    final case class Handle[+A](a: A) extends Message[A]
  }

  def create[F[_], K, A](
    idleTimeout: FiniteDuration
  )(create: K => F[Actor[F, A]])(implicit F: Concurrent[F], timer: Timer[F]): F[Actor[F, (K, A)]] =
    Actor
      .resource[F, (K, Message[A])] { self =>
        import Message._
        Resource[F, Receive[F, (K, Message[A])]] {
          for {
            workers <- F.delay(scala.collection.mutable.Map.empty[K, Actor[F, A]])
            receive = Receive[(K, Message[A])] {
              case (key, Handle(a)) =>
                val getWorker: F[Actor[F, A]] = F.suspend {
                  workers.get(key) match {
                    case Some(actor) => actor.pure[F]
                    case None =>
                      create(key)
                        .flatMap(
                          Actor.withIdleTimeout(idleTimeout, self.send((key, TerminateWorker)), _)
                        )
                        .flatTap { a =>
                          F.delay(workers.update(key, a))
                        }
                  }
                }

                getWorker.flatMap(_.send(a))
              case (key, TerminateWorker) =>
                F.suspend {
                  val out = workers.get(key).traverse_(_.terminateAndWatch)
                  workers.remove(key)
                  out
                }
            }
            cleanUp = F.suspend(workers.values.toVector.traverse_(_.terminateAndWatch)) >>
              F.delay(workers.clear())

          } yield (receive, cleanUp)
        }
      }
      .map(_.contramap[(K, A)](x => x._1 -> Message.Handle(x._2)))
}
