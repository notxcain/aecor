package aecor.schedule.process

import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import akka.actor.ActorSystem
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.effect.kernel.Async
import cats.effect.LiftIO
import cats.effect.std.Dispatcher
import cats.syntax.functor._

import scala.collection.immutable._
import scala.concurrent.duration._

object PeriodicProcessRuntime {
  def apply[F[_]: Async: LiftIO](name: String, tickInterval: FiniteDuration, processCycle: F[Unit])(
    implicit materializer: Materializer
  ): F[PeriodicProcessRuntime[F]] =
    Dispatcher[F].allocated
      .map(_._1)
      .map(
        dispatcher => new PeriodicProcessRuntime[F](name, tickInterval, processCycle, dispatcher)
      )
}

class PeriodicProcessRuntime[F[_]: Async: LiftIO](
  name: String,
  tickInterval: FiniteDuration,
  processCycle: F[Unit],
  dispatcher: Dispatcher[F]
)(implicit materializer: Materializer) {
  private def source =
    Source
      .tick(0.seconds, tickInterval, processCycle)
      .mapAsync(1)(dispatcher.unsafeToFuture)
      .mapMaterializedValue(_ => NotUsed)

  def run(system: ActorSystem): F[DistributedProcessing.KillSwitch[F]] =
    DistributedProcessing(system)
      .start[F](s"$name-Process", List(AkkaStreamProcess[F](source)))
}
