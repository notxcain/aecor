package aecor.runtime.queue

import cats.effect.implicits._
import cats.effect.{Concurrent, ContextShift}
import cats.implicits._
import fs2.concurrent.{Queue, SignallingRef}
import fs2._

private [queue] trait Actor[F[_], A] { outer =>
  def send(message: A): F[Unit]
  def terminate: F[Unit]
  def watchTermination: F[Unit]
  def contramap[B](f: B => A): Actor[F, B] = new Actor[F, B] {
    override def send(message: B): F[Unit] = outer.send(f(message))
    override def terminate: F[Unit] = outer.terminate
    override def watchTermination: F[Unit] = outer.watchTermination
  }
}

private [queue] object Actor {
  object Receive {
    final class Builder[A] {
      def apply[F[_]](f: A => F[Unit]): A => F[Unit] = f
    }
    private val instance: Builder[Any] = new Builder[Any]
    def apply[A]: Builder[A] = instance.asInstanceOf[Builder[A]]
  }
  trait Context[F[_], A] {
    def send(a: A): F[Unit]
  }
  def create[F[_], A](init: Context[F, A] => F[A => F[Unit]])(implicit F: Concurrent[F],
                                             context: ContextShift[F]): F[Actor[F, A]] =
    for {
      mailbox <- Queue.unbounded[F, A]
      actorContext = new Context[F, A] {
        override def send(a: A): F[Unit] = mailbox.enqueue1(a)
      }
      killed <- SignallingRef[F, Boolean](false)
      runloop <- context.shift >>
        {
          val rec = Stream.force {
            mailbox.dequeue1.flatMap { a =>
              init(actorContext).map { handle =>
                (Stream.emit(a) ++ mailbox.dequeue).interruptWhen(killed)
                  .evalMap(handle)
              }
            }
          }
          rec.handleErrorWith(_ => rec)
        }.compile.drain.start

    } yield
      new Actor[F, A] {
        override def send(message: A): F[Unit] =
          F.ifM(killed.get)(F.raiseError(new IllegalStateException("Actor terminated")), context.shift >> mailbox.enqueue1(message))

        override def terminate: F[Unit] =
          killed.set(true) >> watchTermination.attempt.void

        override def watchTermination: F[Unit] =
          runloop.join
      }
  def ignore[F[_], A](implicit F: Concurrent[F],
                      context: ContextShift[F]): F[Actor[F, A]] = create[F, A](ctx => { _: A => ().pure[F] }.pure[F])
}
