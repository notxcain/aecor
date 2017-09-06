package aecor.tests

import akka.actor.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._

object DistributedSourceWorkerSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        cluster.system-name=test
        akka.persistence.journal.plugin=akka.persistence.journal.inmem
        akka.persistence.snapshot-store.plugin=akka.persistence.no-snapshot-store
        aecor.akka-runtime.idle-timeout = 1s
        cluster.seed-nodes = ["akka://test@127.0.0.1:51000"]
     """).withFallback(ConfigFactory.load())
}

class DistributedSourceWorkerSpec
    extends TestKit(ActorSystem("test", GenericRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val sink = Source.queue(10, OverflowStrategy.backpressure)

}
