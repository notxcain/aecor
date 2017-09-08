package aecor.tests

import aecor.data.Tagging
import aecor.tests.e2e.{ CounterEvent, CounterId, CounterOp, CounterOpHandler }
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import monix.eval.Task
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuiteLike, Matchers }
import monix.cats._
import aecor.effect.monix._
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntime, AkkaPersistenceRuntimeUnit }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import monix.execution.Scheduler

import scala.concurrent.duration._

object AkkaPersistenceRuntimeSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        cluster.system-name=test
        cluster.port = 51000
        aecor.generic-akka-runtime.idle-timeout = 1s
        cluster {
          seed-nodes = [
            "akka.tcp://test@127.0.0.1:51000"
          ]
        }
     """).withFallback(CassandraLifecycle.config).withFallback(ConfigFactory.load())
}

class AkkaPersistenceRuntimeSpec
    extends TestKit(ActorSystem("test", AkkaPersistenceRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with CassandraLifecycle {
  import CounterOp._

  override def systemName = system.name

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  implicit val scheduler = Scheduler(system.dispatcher)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  val runtime = AkkaPersistenceRuntime(system)

  val deploy = runtime.deploy(
    AkkaPersistenceRuntimeUnit(
      "Counter",
      CounterOpHandler.behavior[Task],
      Tagging.const[CounterId](CounterEvent.tag)
    )
  )

  test("Runtime should work") {
    val program = for {
      counters <- deploy
      _ <- counters("1")(Increment)
      _ <- counters("2")(Increment)
      _2 <- counters("2")(GetValue)
      _ <- counters("1")(Decrement)
      _1 <- counters("1")(GetValue)
      afterPassivation <- counters("2")(GetValue).delayExecution(2.seconds)
    } yield (_1, _2, afterPassivation)

    program.runAsync.futureValue shouldEqual ((0L, 1L, 1L))
  }
  test("Journal should work") {
    implicit val materializer = ActorMaterializer()
    val journal = runtime.journal[String, CounterEvent]
    val value = journal.currentEventsByTag(CounterEvent.tag, None).runWith(Sink.seq).futureValue

    value.size shouldBe 3
  }
}
