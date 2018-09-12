package aecor.tests

import aecor.data.Behavior
import aecor.runtime.akkageneric.GenericAkkaRuntime
import aecor.testkit.StateRuntime
import aecor.tests.e2e._
import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.effect.IO
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike, Matchers}
import cats.implicits._
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

  val timer = IO.timer(system.dispatcher)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val behavior: IO[Counter[IO]] =
    Behavior.fromState(
      Vector.empty[CounterEvent],
      StateRuntime.single(CounterBehavior.instance[IO])
    )

  val deployCounters =
    GenericAkkaRuntime(system).runBehavior("Counter", (_: CounterId) => behavior)

  test("Runtime should work") {
    val program = for {
      counters <- deployCounters
      first = counters(CounterId("1"))
      second = counters(CounterId("2"))
      _ <- first.increment
      _ <- second.increment
      _2 <- second.value
      _ <- first.decrement
      _1 <- first.value
      afterPassivation <- timer.sleep(2.seconds) >> second.value
    } yield (_1, _2, afterPassivation)

    val (first, second, afterPassivation) = program.unsafeRunSync()
    first shouldBe 0L
    second shouldBe 1L
    afterPassivation shouldBe 0
  }
}
