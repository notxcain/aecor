package aecor.streaming

import aecor.streaming.StreamSupervisor.StreamKillSwitch
import akka.actor.{ ActorSystem, SupervisorStrategy }
import akka.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import akka.pattern.{ BackoffSupervisor, ask }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.Timeout

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.{ FiniteDuration, _ }
import cats.implicits._

class StreamSupervisor(system: ActorSystem) {
  def startClusterSingleton[A, SM, FM](
    name: String,
    source: Source[A, SM],
    flow: Flow[A, Unit, FM],
    settings: StreamSupervisorSettings =
      StreamSupervisorSettings(3.seconds, 10.seconds, 0.2, ClusterSingletonManagerSettings(system))
  )(implicit mat: Materializer): StreamKillSwitch = {
    import mat.executionContext
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
      (ref ? StreamSupervisorActor.Shutdown).map(_ => ())
    }
  }
}

object StreamSupervisor {
  def apply(system: ActorSystem): StreamSupervisor = new StreamSupervisor(system)
  final case class StreamKillSwitch(trigger: Timeout => Future[Unit])
}

final case class StreamSupervisorSettings(
  minBackoff: FiniteDuration,
  maxBackoff: FiniteDuration,
  randomFactor: Double,
  clusterSingletonManagerSettings: ClusterSingletonManagerSettings
)
