package aecor.schedule.process

import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.util.effect._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.effect.Effect

import scala.collection.immutable._
import scala.concurrent.duration.{ FiniteDuration, _ }

object PeriodicProcessRuntime {
  def apply[F[_]: Effect](name: String, tickInterval: FiniteDuration, processCycle: F[Unit])(
    implicit materializer: Materializer
  ): PeriodicProcessRuntime[F] =
    new PeriodicProcessRuntime[F](name, tickInterval, processCycle)
}

class PeriodicProcessRuntime[F[_]: Effect](
  name: String,
  tickInterval: FiniteDuration,
  processCycle: F[Unit]
)(implicit materializer: Materializer) {

  private def source =
    Source
      .tick(0.seconds, tickInterval, processCycle)
      .mapAsync(1)(_.unsafeToFuture())
      .mapMaterializedValue(_ => NotUsed)

  def run(system: ActorSystem): F[DistributedProcessing.KillSwitch[F]] =
    DistributedProcessing(system)
      .start[F](s"$name-Process", List(AkkaStreamProcess[F](source)))

}
