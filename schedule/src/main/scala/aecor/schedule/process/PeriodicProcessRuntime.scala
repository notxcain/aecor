package aecor.schedule.process

import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.streaming.StreamSupervisor
import aecor.streaming.StreamSupervisor.StreamKillSwitch
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import cats.Monad
import Async.ops._
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import cats.implicits._

object PeriodicProcessRuntime {
  def apply[F[_]: Async: CaptureFuture: Capture: Monad](
    name: String,
    tickInterval: FiniteDuration,
    processCycle: F[Unit]
  )(implicit materializer: Materializer): PeriodicProcessRuntime[F] =
    new PeriodicProcessRuntime[F](name, tickInterval, processCycle)
}

class PeriodicProcessRuntime[F[_]: Async: CaptureFuture: Capture: Monad](
  name: String,
  tickInterval: FiniteDuration,
  processCycle: F[Unit]
)(implicit materializer: Materializer) {

  private def source =
    Source
      .single(())
      .mapAsync(1) { _ =>
        eachRefreshInterval(processCycle).unsafeRun
      }

  private def eachRefreshInterval(f: F[Unit]): F[Unit] =
    for {
      _ <- f
      _ <- afterRefreshInterval {
            eachRefreshInterval(f)
          }
    } yield ()

  private def afterRefreshInterval[A](f: => F[A]): F[A] =
    CaptureFuture[F].captureFuture {
      val p = Promise[A]
      materializer.scheduleOnce(tickInterval, new Runnable {
        override def run(): Unit = {
          p.completeWith(f.unsafeRun)
          ()
        }
      })
      p.future
    }

  def run(system: ActorSystem): F[StreamKillSwitch[F]] =
    StreamSupervisor(system)
      .startClusterSingleton(s"$name-Process", source, Flow[Unit])
}
