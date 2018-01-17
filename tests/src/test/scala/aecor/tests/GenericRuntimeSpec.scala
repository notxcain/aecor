package aecor.tests

import aecor.data.Behavior

import aecor.runtime.akkageneric.GenericAkkaRuntime
import aecor.testkit.StateRuntime
import aecor.tests.e2e.CounterOp.{ Decrement, GetValue, Increment }
import aecor.tests.e2e._
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }

import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._

object GenericRuntimeSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        cluster.system-name=test
        cluster.port = 51001
        aecor.generic-akka-runtime.idle-timeout = 1s
        cluster {
          seed-nodes = [
            "akka.tcp://test@127.0.0.1:51001"
          ]
        }
     """).withFallback(ConfigFactory.load())
}

class GenericRuntimeSpec
    extends TestKit(ActorSystem("test", GenericRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  implicit val scheduler = Scheduler(system.dispatcher)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val behavior: Behavior[Task, CounterOp] =
    Behavior.fromState(Vector.empty[CounterEvent], StateRuntime.single(CounterBehavior[Task]))

  val deployCounters =
    GenericAkkaRuntime[Task](system).deploy("Counter", (_: CounterId) => behavior)

  test("Runtime should work") {
    val program = for {
      counters <- deployCounters
      first = counters(CounterId("1"))
      second = counters(CounterId("2"))
      _ <- first(Increment)
      _ <- second(Increment)
      _2 <- second(GetValue)
      _ <- first(Decrement)
      _1 <- first(GetValue)
      afterPassivation <- second(GetValue).delayExecution(2.seconds)
    } yield (_1, _2, afterPassivation)

    val (first, second, afterPassivation) = program.runAsync.futureValue
    first shouldBe 0L
    second shouldBe 1L
    afterPassivation shouldBe 0
  }
}
