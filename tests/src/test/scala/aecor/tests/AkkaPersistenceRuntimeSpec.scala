package aecor.tests

import aecor.data.Tagging
import aecor.tests.e2e.{ CounterEvent, CounterOp, CounterOpHandler }
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import monix.eval.Task
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }
import monix.cats._
import aecor.effect.monix._
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntime, AkkaPersistenceRuntimeUnit }
import monix.execution.Scheduler

import scala.concurrent.duration._

object AkkaPersistenceRuntimeSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        cluster.system-name=test
        akka.persistence.journal.plugin=akka.persistence.journal.inmem
        akka.persistence.snapshot-store.plugin=akka.persistence.no-snapshot-store
        aecor.akka-runtime.idle-timeout = 1s
        cluster.seed-nodes = ["akka://test@127.0.0.1:51000"]
     """).withFallback(ConfigFactory.load())
}

class AkkaPersistenceRuntimeSpec
    extends TestKit(ActorSystem("test", AkkaPersistenceRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  implicit val scheduler = Scheduler(system.dispatcher)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val runtime = AkkaPersistenceRuntime(system)

  val deploy = runtime.deploy(
    AkkaPersistenceRuntimeUnit(
      "Counter",
      CounterOp.correlation,
      CounterOpHandler.behavior[Task],
      Tagging.const(CounterEvent.tag)
    )
  )

  test("Runtime should work") {
    val program = for {
      ar <- deploy.start
      _ <- ar(CounterOp.Increment("1"))
      _ <- ar(CounterOp.Increment("2"))
      _2 <- ar(CounterOp.GetValue("2"))
      _ <- ar(CounterOp.Decrement("1"))
      _1 <- ar(CounterOp.GetValue("1"))
      afterPassivation <- ar(CounterOp.GetValue("2")).delayExecution(2.seconds)
    } yield (_1, _2, afterPassivation)

    val (_1, _2, afterPassivation) = program.runAsync.futureValue
    _1 shouldBe 0L
    _2 shouldBe 1L
    afterPassivation shouldBe 1L
  }
}
