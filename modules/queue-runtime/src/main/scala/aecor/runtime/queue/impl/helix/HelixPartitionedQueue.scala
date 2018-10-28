package aecor.runtime.queue.impl.helix

import java.net.InetSocketAddress

import aecor.runtime.queue.PartitionedQueue
import aecor.runtime.queue.PartitionedQueue.Instance
import aecor.runtime.queue.impl.helix.HelixPartitionedQueue._
import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.implicits._
import fs2._
import fs2.concurrent.{ NoneTerminatedQueue, Queue }
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
import org.apache.helix.spectator.RoutingTableProvider
import org.apache.helix.{ HelixManagerFactory, InstanceType, NotificationContext }
import org.http4s.Uri.{ Authority, Scheme }
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.{ EntityDecoder, EntityEncoder, HttpRoutes, Method, Request, Uri }
import scodec.Codec

import scala.concurrent.ExecutionContext

final class HelixPartitionedQueue[F[_], A: Codec: EntityDecoder[F, ?]: EntityEncoder[F, ?]](
  zookeeperHosts: Set[ZookeeperHost],
  clusterName: ClusterName,
  localHost: InstanceHost,
  partitionCount: Int,
  partitioner: A => Int,
  bindAddress: InetSocketAddress,
  bindPath: String
)(implicit F: ConcurrentEffect[F], ec: ExecutionContext)
    extends PartitionedQueue[F, A]
    with Http4sDsl[F] {

  override def start: Resource[F, PartitionedQueue.Instance[F, A]] =
    for {
      incomingQueue <- Resource.liftF(Queue.unbounded[F, A])
      partitionQueue <- Resource.liftF(Queue.unbounded[F, Stream[F, A]])
      client <- startClient
      _ <- startServer(incomingQueue.enqueue1)
      _ <- startProcessing(incomingQueue, client, partitionQueue.enqueue1)
    } yield Instance(incomingQueue.enqueue1, partitionQueue.dequeue)

  def startProcessing(incomingQueue: Queue[F, A],
                      send: (InstanceHost, A) => F[Unit],
                      publishPartition: Stream[F, A] => F[Unit]): Resource[F, Unit] =
    for {
      (partitionEvents, rtp) <- connect
      _ <- Resource[F, Unit] {
            for {
              shards <- F.delay(scala.collection.mutable.Map.empty[Int, NoneTerminatedQueue[F, A]])
              fiber <- partitionEvents
                        .either(incomingQueue.dequeue)
                        .evalMap {
                          case Left(x @ PartitionEvent(partition, event, commit)) =>
                            println(x)
                            (event match {
                              case PartitionAssignmentEvent.PartitionAssigned =>
                                Queue.noneTerminated[F, A].flatMap { queue =>
                                  F.delay(shards.update(partition, queue)) >> publishPartition(
                                    queue.dequeue
                                  )
                                } >> commit
                              case PartitionAssignmentEvent.PartitionRevoked =>
                                F.suspend(
                                  shards
                                    .get(partition)
                                    .traverse(q => q.enqueue1(none) >> q.dequeue.compile.drain)
                                ) >> F.delay(shards -= partition) >> commit
                            }) >> F.delay(println(shards))
                          case Right(a) =>
                            val partition = partitioner(a)
                            shards.get(partition) match {
                              case Some(partitionQueue) =>
                                partitionQueue
                                  .enqueue1(a.some)
                              case None =>
                                val instanceConfig = rtp
                                  .getInstancesForResource(
                                    resourceName.value,
                                    s"${resourceName.value}_$partition",
                                    "ONLINE"
                                  )
                                  .get(0)
                                val instanceHost = InstanceHost(
                                  instanceConfig.getHostName,
                                  instanceConfig.getPort.toInt
                                )
                                if (instanceHost == localHost)
                                  incomingQueue.enqueue1(a)
                                else
                                  send(instanceHost, a)
                            }
                        }
                        .compile
                        .drain
                        .start
            } yield ((), fiber.cancel)
          }
    } yield ()

  def startClient: Resource[F, (InstanceHost, A) => F[Unit]] = {
    def createResponseSender(client: Client[F]): (InstanceHost, A) => F[Unit] = {
      case (address, a) =>
        val uri = Uri(
          Some(Scheme.http),
          Some(Authority(host = Uri.IPv4(address.hostname), port = Some(address.port))),
          path = s"/$bindPath"
        )
        client.fetch(Request[F](Method.POST, uri).withEntity(a))(_ => F.unit).start.void
    }

    BlazeClientBuilder[F](ec).resource
      .flatMap(c => Resource.pure(createResponseSender(c)))
  }
  def startServer(f: A => F[Unit]): Resource[F, Unit] = {
    val routes = HttpRoutes.of[F] {
      case req @ POST -> Root =>
        for {
          a <- req.as[A]
          _ <- f(a)
          resp <- Accepted()
        } yield resp
    }
    BlazeBuilder[F]
      .bindSocketAddress(bindAddress)
      .mountService(routes, s"/$bindPath")
      .resource
      .void
  }

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
              partitionCount,
              StateModelDef,
              RebalanceMode.FULL_AUTO.toString,
              classOf[CrushEdRebalanceStrategy].getName
            )
          admin.close()
        }
      }
      .attempt
      .void

  def connect: Resource[F, (Stream[F, PartitionEvent[F]], RoutingTableProvider)] =
    Resource[F, (Stream[F, PartitionEvent[F]], RoutingTableProvider)] {
      Queue.unbounded[F, PartitionEvent[F]].flatMap { queue =>
        F.catchNonFatal {
            val admin = new ZKHelixAdmin(zookeeperHosts.mkString(","))
            val instances = admin.getInstancesInCluster(clusterName.value)
            if (!instances.contains(localHost.instanceName)) {
              val config = new InstanceConfig(localHost.instanceName)
              config.setHostName(localHost.hostname)
              config.setPort(localHost.port.toString)
              config.setInstanceEnabled(true)
              admin.addInstance(clusterName.value, config)
            }
            admin.rebalance(clusterName.value, resourceName, 1)

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

            manager
          }
          .flatMap { manager =>
            F.catchNonFatal(manager.connect())
              .map(
                _ =>
                  (queue.dequeue, new RoutingTableProvider(manager)) -> F.delay {
                    manager.disconnect()
                }
              )
          }
      }
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
                                        commit: F[Unit])
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
