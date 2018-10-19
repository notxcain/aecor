package aecor.runtime.queue.impl
import aecor.runtime.queue.PartitionedQueue
import aecor.runtime.queue.impl.HelixPartitionedQueue._
import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.implicits._
import fs2._
import fs2.concurrent.Queue
import org.apache.helix.controller.HelixControllerMain
import org.apache.helix.controller.rebalancer.strategy.CrushEdRebalanceStrategy
import org.apache.helix.manager.zk.ZKHelixAdmin
import org.apache.helix.model.IdealState.RebalanceMode
import org.apache.helix.model.{ InstanceConfig, Message, OnlineOfflineSMD }
import org.apache.helix.participant.DistClusterControllerStateModelFactory
import org.apache.helix.participant.statemachine.{
  StateModel,
  StateModelFactory,
  StateModelInfo,
  Transition
}
import org.apache.helix.{ HelixManagerFactory, InstanceType, NotificationContext }

final class HelixPartitionedQueue[F[_], A](zookeeperHosts: Set[ZookeeperHost],
                                           clusterName: ClusterName,
                                           localHost: InstanceHost)(implicit F: ConcurrentEffect[F])
    extends PartitionedQueue[F, A] {
  override def start: Resource[F, PartitionedQueue.Instance[F, A]] = null

  private val StateModelDef = OnlineOfflineSMD.name
  val resourceName = "Payment"
  def setup: F[Unit] =
    F.catchNonFatal {
        scala.concurrent.blocking {
          val admin = new ZKHelixAdmin(zookeeperHosts.mkString(","))
          admin.addCluster(clusterName.value)
          admin.addStateModelDef(clusterName.value, StateModelDef, OnlineOfflineSMD.build())
          admin
            .addResource(
              clusterName.value,
              resourceName,
              40,
              StateModelDef,
              RebalanceMode.FULL_AUTO.toString,
              classOf[CrushEdRebalanceStrategy].getName
            )
          admin.close()
        }
      }
      .attempt
      .void

  def connect: Resource[F, Stream[F, PartitionEvent[F]]] =
    Resource[F, Stream[F, PartitionEvent[F]]] {
      Queue.unbounded[F, PartitionEvent[F]].flatMap { queue =>
        F.catchNonFatal {
          val admin = new ZKHelixAdmin(zookeeperHosts.mkString(","))
          val instances = admin.getInstancesInCluster(clusterName.value)
          if (!instances.contains(localHost.instanceName)) {
            val config = new InstanceConfig(localHost.instanceName)
            config.setHostName(localHost.instanceName)
            config.setInstanceEnabled(true)
            admin.addInstance(clusterName.value, config)
          }
          admin.rebalance(clusterName.value, resourceName, 1)
          admin.close()

          val manager = HelixManagerFactory.getZKHelixManager(
            clusterName.value,
            localHost.instanceName,
            InstanceType.CONTROLLER_PARTICIPANT,
            zookeeperHosts.mkString(","),
          )

          val stateMach = manager.getStateMachineEngine
          stateMach.registerStateModelFactory(
            "LeaderStandby",
            new DistClusterControllerStateModelFactory(zookeeperHosts.mkString(","))
          )
          stateMach
            .registerStateModelFactory(
              StateModelDef,
              new PartitionAssignmentFactory[F](
                (pt, e) => PartitionEvent.create(pt, e).flatMap(x => queue.offer1(x._1) >> x._2)
              )
            )
          manager.connect()
          queue.dequeue -> F.delay(manager.disconnect())
        }
      }
    }

  def startController: F[Unit] = F.delay {
    HelixControllerMain.startHelixController(
      zookeeperHosts.mkString(","),
      clusterName.value,
      clusterName.value,
      HelixControllerMain.DISTRIBUTED
    )
    ()
  }
}

object HelixPartitionedQueue {
  final case class ZookeeperHost(hostname: String, port: Int) {
    override def toString: String = s"$hostname:$port"
  }
  object ZookeeperHost {
    def local: ZookeeperHost = ZookeeperHost("localhost", 2181)
  }
  final case class InstanceHost(hostname: String, port: Int) {
    override def toString: String = s"$hostname:$port"
    def instanceName: String = s"${hostname}_$port"
  }
  final case class ClusterName(value: String) extends AnyVal

  @StateModelInfo(states = Array("OFFLINE", "ONLINE", "DROPPED"), initialState = "OFFLINE")
  final class PartitionAssignment[F[_]: Effect](eventHandler: PartitionAssignmentEvent => F[Unit])
      extends StateModel {

    @Transition(from = "OFFLINE", to = "ONLINE")
    def onBecomeOnlineFromOffline(message: Message, context: NotificationContext): Unit =
      eventHandler(PartitionAssignmentEvent.PartitionAssigned).toIO.unsafeRunSync()

    @Transition(from = "ONLINE", to = "OFFLINE")
    def onBecomeOfflineFromOnline(message: Message, context: NotificationContext): Unit =
      eventHandler(PartitionAssignmentEvent.PartitionRevoked).toIO.unsafeRunSync()

    @Transition(from = "OFFLINE", to = "DROPPED")
    def onBecomeDroppedFromOffline(message: Message, context: NotificationContext): Unit = ()
  }

  sealed abstract class PartitionAssignmentEvent
  object PartitionAssignmentEvent {
    final case object PartitionAssigned extends PartitionAssignmentEvent
    final case object PartitionRevoked extends PartitionAssignmentEvent
  }

  final case class PartitionEvent[F[_]](partition: Int,
                                        event: PartitionAssignmentEvent,
                                        complete: F[Unit])
  object PartitionEvent {
    def create[F[_]: Concurrent](partition: Int,
                                 event: PartitionAssignmentEvent): F[(PartitionEvent[F], F[Unit])] =
      Deferred[F, Unit].map(d => (PartitionEvent(partition, event, d.complete(())), d.get))
  }

  final class PartitionAssignmentFactory[F[_]: Effect](
    eventHandler: (Int, PartitionAssignmentEvent) => F[Unit]
  ) extends StateModelFactory[StateModel] {

    override def createNewStateModel(resourceName: String, partitionName: String): StateModel = {
      val partition = partitionName.splitAt(partitionName.lastIndexOf('_'))._2.tail.toInt
      new PartitionAssignment(eventHandler(partition, _))
    }
  }
}
