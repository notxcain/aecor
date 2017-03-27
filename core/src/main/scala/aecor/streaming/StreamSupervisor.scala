package aecor.streaming

import aecor.effect.{ Capture, CaptureFuture }
import aecor.streaming.StreamSupervisor.StreamKillSwitch
import akka.actor.{ ActorSystem, SupervisorStrategy }
import akka.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import akka.pattern.{ BackoffSupervisor, ask }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.Timeout
import cats.Functor
import cats.implicits._

import scala.concurrent.duration.{ FiniteDuration, _ }

class StreamSupervisor(system: ActorSystem) {
  def startClusterSingleton[F[_]: Capture: CaptureFuture: Functor, A, SM, FM](
    name: String,
    source: Source[A, SM],
    flow: Flow[A, Unit, FM],
    settings: StreamSupervisorSettings =
      StreamSupervisorSettings(3.seconds, 10.seconds, 0.2, ClusterSingletonManagerSettings(system))
  )(implicit mat: Materializer): F[StreamKillSwitch[F]] = Capture[F].capture {
    val props = ClusterSingletonManager.props(
      singletonProps = BackoffSupervisor.propsWithSupervisorStrategy(
        StreamSupervisorActor.props(source, flow),
        "stream",
        settings.minBackoff,
        settings.maxBackoff,
        settings.randomFactor,
        SupervisorStrategy.stoppingStrategy
      ),
      terminationMessage = StreamSupervisorActor.Shutdown,
      settings = settings.clusterSingletonManagerSettings
    )
    val ref = system.actorOf(props, name)
    StreamKillSwitch { implicit timeout: Timeout =>
      CaptureFuture[F].captureF(ref ? StreamSupervisorActor.Shutdown).void
    }
  }
}

object StreamSupervisor {
  def apply(system: ActorSystem): StreamSupervisor = new StreamSupervisor(system)
  final case class StreamKillSwitch[F[_]](trigger: Timeout => F[Unit])
}

final case class StreamSupervisorSettings(
  minBackoff: FiniteDuration,
  maxBackoff: FiniteDuration,
  randomFactor: Double,
  clusterSingletonManagerSettings: ClusterSingletonManagerSettings
)
