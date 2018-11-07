package aecor.runtime.queue

import cats.effect.implicits._
import cats.effect.{ Concurrent, Fiber, Resource, Timer }
import cats.implicits._
import fs2.concurrent.Queue
import aecor.data.PairE
import cats.effect.concurrent.{ Deferred, Ref }
import cats.tagless.FunctorK
import cats.{ Applicative, ~> }
import cats.tagless.syntax.functorK._
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

  sealed abstract class Context[F[_], A] {
    def send(a: A): F[Unit]
    def terminate: F[Unit]
  }

  def resource[F[_], A](
    init: Context[F, A] => Resource[F, A => F[Unit]]
  )(implicit F: Concurrent[F]): F[Actor[F, A]] =
    for {
      mailbox <- Queue.noneTerminated[F, A]

      actorContext = new Context[F, A] {
        override def send(a: A): F[Unit] =
          mailbox.enqueue1(a.some)

        override def terminate: F[Unit] =
          mailbox.enqueue1(none)
      }

      runloop <- {
        def run: F[Unit] =
          init(actorContext)
            .use { handle =>
              mailbox.dequeue.evalMap(handle).compile.drain
            }
            .handleErrorWith(_ => run)
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

  def create[F[_]: Concurrent, A](init: Context[F, A] => F[A => F[Unit]]): F[Actor[F, A]] =
    resource[F, A](ctx => Resource.liftF(init(ctx)))

  def void[F[_]: Concurrent, A]: F[Actor[F, A]] =
    resource[F, A](_ => Resource.pure(Receive.void[F, A]))

  def wrap[F[_]: Concurrent, M[_[_]]: FunctorK](load: F[M[F]]): F[M[F]] =
    for {
      actor <- Actor.create[F, PairE[F, Deferred[F, ?]]] { _ =>
                Receive[PairE[F, Deferred[F, ?]]] { pair =>
                  pair.first.flatMap(pair.second.complete)
                }.pure[F]
              }
      mf <- load
    } yield
      mf.mapK {
        new (F ~> F) {
          override def apply[A](fa: F[A]): F[A] =
            for {
              deferred <- Deferred[F, A]
              _ <- actor.send(PairE(fa, deferred))
              out <- deferred.get
            } yield out
        }
      }

  def withIdleTimeout[F[_]: Concurrent, A](
    duration: FiniteDuration,
    onTimeout: F[Unit],
    actor: Actor[F, A]
  )(implicit timer: Timer[F]): F[Actor[F, A]] =
    Actor.resource[F, A] { _ =>
      Resource[F, Receive[F, A]] {
        for {
          cancellationFiber <- Ref[F].of(Fiber(().pure[F], ().pure[F]))
          receive = Receive[A] { a =>
            actor.send(a) >> (timer.sleep(duration) >> onTimeout).start
              .flatMap(cancellationFiber.getAndSet)
              .flatMap(_.cancel)
          }
        } yield (receive, cancellationFiber.get.flatMap(_.cancel) >> actor.terminateAndWatch)
      }
    }
}
