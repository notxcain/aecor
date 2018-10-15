package aecor.runtime.queue

import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Timer}
import cats.implicits._
import fs2.concurrent.Queue
import fs2._
import _root_.io.aecor.liberator.ReifiedInvocations
import _root_.io.aecor.liberator.Invocation
import aecor.data.PairE
import cats.effect.concurrent.{Deferred, Ref}
import cats.{Applicative, ~>}

import scala.concurrent.duration.FiniteDuration

private[queue] trait Actor[F[_], A] { outer =>
  def send(message: A): F[Unit]
  final def terminateAndWatch(implicit F: Applicative[F]): F[Unit] = terminate *> watchTermination
  def terminate: F[Unit]
  def watchTermination: F[Unit]
  final def contramap[B](f: B => A): Actor[F, B] = new Actor[F, B] {
    override def send(message: B): F[Unit] = outer.send(f(message))
    override def terminate: F[Unit] = outer.terminate
    override def watchTermination: F[Unit] = outer.watchTermination
  }
}

private[queue] object Actor {

  type Receive[F[_], A] = A => F[Unit]

  object Receive {
    final class Builder[A] {
      def apply[F[_]](f: A => F[Unit]): A => F[Unit] = f
    }
    private val instance: Builder[Any] = new Builder[Any]
    def apply[A]: Builder[A] = instance.asInstanceOf[Builder[A]]
    def void[F[_], A](implicit F: Applicative[F]): A => F[Unit] = _ => F.pure(())
  }

  trait Context[F[_], A] {
    def send(a: A): F[Unit]
    def terminate: F[Unit]
  }

  def resource[F[_], A](init: Context[F, A] => Resource[F, A => F[Unit]])(implicit F: Concurrent[F]): F[Actor[F, A]] =
    for {
      mailbox <- Queue.unbounded[F, Option[A]]

      actorContext = new Context[F, A] {
        override def send(a: A): F[Unit] =
          mailbox.enqueue1(a.some)

        override def terminate: F[Unit] =
          mailbox.enqueue1(none)
      }

      runloop <- {
        def run: F[Unit] = init(actorContext).use { handle =>
          mailbox.dequeue.unNoneTerminate.evalMap(handle).compile.drain
        }.handleErrorWith(_ => run)

        val _ =
          Stream.resource(init(actorContext)).flatMap { handle =>
            mailbox.dequeue.unNoneTerminate.evalMap(handle)
          }.compile.drain.handleErrorWith(_ => run)

        run.start
      }
    } yield
      new Actor[F, A] {
        override def send(message: A): F[Unit] =
          actorContext.send(message)

        override def terminate: F[Unit] =
          actorContext.terminate

        override def watchTermination: F[Unit] =
          runloop.join
      }

  def create[F[_]: Concurrent, A](
    init: Context[F, A] => F[A => F[Unit]]
  ): F[Actor[F, A]] =
    resource[F, A](ctx => Resource.liftF(init(ctx)))

  def void[F[_]: Concurrent, A]: F[Actor[F, A]] =
    resource[F, A](_ => Resource.pure(Receive.void[F, A]))

  def wrap[F[_], M[_[_]]](load: M[F] => F[M[F]])(implicit F: Concurrent[F],
                                                 M: ReifiedInvocations[M]): F[M[F]] =
    for {
      self <- Deferred[F, M[F]]
      actor <- Actor.create[F, PairE[Invocation[M, ?], Deferred[F, ?]]] { _ =>
                self.get.flatMap(load).map { mf =>
                  Receive[PairE[Invocation[M, ?], Deferred[F, ?]]] { pair =>
                    pair.first.invoke(mf).flatMap(pair.second.complete)
                  }
                }
              }
      out = M.mapInvocations {
        new (Invocation[M, ?] ~> F) {
          override def apply[A](invocation: Invocation[M, A]): F[A] =
            for {
              deferred <- Deferred[F, A]
              _ <- actor.send(PairE(invocation, deferred))
              out <- deferred.get
            } yield out
        }
      }
      _ <- self.complete(out)
    } yield out

  def withIdleTimeout[F[_]: Concurrent, A](duration: FiniteDuration, onTimeout: F[Unit], actor: Actor[F, A])(implicit timer: Timer[F]): F[Actor[F, A]] =
    Actor.resource[F, A] { _ =>
      Resource[F, Receive[F, A]] {
        for {
          timeoutCancellation <- Ref[F].of(().pure[F])
          receive = Receive[A] {
            a =>
              actor.send(a) >> (timer.sleep(duration) >> onTimeout).start.flatMap(f => timeoutCancellation.getAndSet(f.cancel).flatten)
          }
        } yield (receive, timeoutCancellation.get.flatten >> actor.terminateAndWatch)
      }
    }
}
