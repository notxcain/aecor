package aecor.schedule.process

import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.effect.Async.ops._
import aecor.effect.{ Async, Capture }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import cats.Monad

import scala.collection.immutable._
import scala.concurrent.duration.{ FiniteDuration, _ }

object PeriodicProcessRuntime {
  def apply[F[_]: Async: Capture: Monad](
    name: String,
    tickInterval: FiniteDuration,
    processCycle: F[Unit]
  )(implicit materializer: Materializer): PeriodicProcessRuntime[F] =
    new PeriodicProcessRuntime[F](name, tickInterval, processCycle)
}

class PeriodicProcessRuntime[F[_]: Async: Capture: Monad](
  name: String,
  tickInterval: FiniteDuration,
  processCycle: F[Unit]
)(implicit materializer: Materializer) {

  private def source =
    Source
      .tick(0.seconds, tickInterval, processCycle)
      .mapAsync(1)(_.unsafeRun)
      .mapMaterializedValue(_ => NotUsed)

  def run(system: ActorSystem): F[DistributedProcessing.KillSwitch[F]] =
    DistributedProcessing(system)
      .start[F](s"$name-Process", Seq(AkkaStreamProcess[F](source, Flow[Unit])))

}
