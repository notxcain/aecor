package aecor.schedule.process

import aecor.effect.{ Async, Capture, CaptureFuture }

import scala.collection.immutable._
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import cats.Monad
import Async.ops._
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import akka.NotUsed

import scala.concurrent.duration.FiniteDuration

import scala.concurrent.duration._

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
      .tick(0.seconds, tickInterval, processCycle)
      .mapAsync(1)(_.unsafeRun)
      .mapMaterializedValue(_ => NotUsed)

  def run(system: ActorSystem): F[DistributedProcessing.ProcessKillSwitch[F]] =
    DistributedProcessing(system)
      .start[F](s"$name-Process", Seq(AkkaStreamProcess[F](source, Flow[Unit])))

}
