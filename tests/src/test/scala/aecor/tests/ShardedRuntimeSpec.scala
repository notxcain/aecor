package aecor.tests

import aecor.data.{ Behavior, EventsourcedBehavior }
import aecor.effect.monix._
import aecor.runtime.akkageneric.GenericAkkaRuntime
import aecor.testkit.StateRuntime
import aecor.tests.e2e.{ CounterEvent, CounterOp, CounterOpHandler, CounterState }
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import monix.cats._
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._

object ShardedRuntimeSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        cluster.system-name=test
        akka.persistence.journal.plugin=akka.persistence.journal.inmem
        akka.persistence.snapshot-store.plugin=akka.persistence.no-snapshot-store
        aecor.akka-runtime.idle-timeout = 1s
                                                  cluster.seed-nodes = ["akka://test@127.0.0.1:51000"]
     """).withFallback(ConfigFactory.load())
}

class ShardedRuntimeSpec
    extends TestKit(ActorSystem("test", ShardedRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  implicit val scheduler = Scheduler(system.dispatcher)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val behavior: Behavior[Task, CounterOp] = Behavior.fromState(
    Vector.empty[CounterEvent],
    StateRuntime.shared(EventsourcedBehavior(CounterOpHandler[Task], CounterState.folder))
  )

  val startRuntime =
    GenericAkkaRuntime[Task](system).start("Counter", CounterOp.correlation, behavior)

  test("Runtime should work") {
    val program = for {
      runtime <- startRuntime
      _ <- runtime(CounterOp.Increment("1"))
      _ <- runtime(CounterOp.Increment("2"))
      second <- runtime(CounterOp.GetValue("2"))
      _ <- runtime(CounterOp.Decrement("1"))
      _1 <- runtime(CounterOp.GetValue("1"))
      secondAfterPassivation <- runtime(CounterOp.GetValue("2")).delayExecution(2.seconds)
    } yield (_1, second, secondAfterPassivation)

    val (_1, _2, afterPassivation) = program.runAsync.futureValue
    _1 shouldBe 0L
    _2 shouldBe 1L
    afterPassivation shouldBe 0
  }
}
