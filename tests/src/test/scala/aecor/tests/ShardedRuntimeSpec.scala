package aecor.tests

import aecor.aggregate.StateRuntime
import aecor.aggregate.runtime.{ GenericAkkaRuntime, StateBehavior }
import aecor.tests.e2e.{ CounterEvent, CounterOp, CounterOpHandler }
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }
import monix.cats.monixToCatsMonad
import aecor.effect.monix._
import cats.implicits._
import scala.concurrent.duration._

object ShardedRuntimeSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        akka.persistence.journal.plugin=akka.persistence.journal.inmem
        akka.persistence.snapshot-store.plugin=akka.persistence.no-snapshot-store
     """).withFallback(ConfigFactory.load())
}

class ShardedRuntimeSpec
    extends TestKit(ActorSystem("aecor-example", ShardedRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  override implicit val patienceConfig = PatienceConfig(4.seconds, 2.second)

  implicit val scheduler = Scheduler(system.dispatcher)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val behavior = StateBehavior(
    StateRuntime.shared(CounterOpHandler[Task]),
    Vector.empty[CounterEvent].pure[Task]
  )

  val startRuntime = GenericAkkaRuntime(system).start("Counter", CounterOp.correlation, behavior)

  test("Runtime should work") {
    val program = for {
      runtime <- startRuntime
      _ <- runtime(CounterOp.Increment("1"))
      _ <- runtime(CounterOp.Increment("2"))
      _2 <- runtime(CounterOp.GetValue("2"))
      _ <- runtime(CounterOp.Decrement("1"))
      _1 <- runtime(CounterOp.GetValue("1"))
      _ <- startRuntime
    } yield (_1, _2)

    val (_1, _2) = program.runAsync.futureValue
    _1 shouldBe 0L
    _2 shouldBe 1L
  }
}
