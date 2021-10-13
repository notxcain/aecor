package aecor.runtime.akkageneric

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.unsafe.IORuntime
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object GenericRuntimeSpec {
  def conf: Config = ConfigFactory
    .parseString(s"""
        cluster.system-name=test
        cluster.port = 51001
        aecor.generic-akka-runtime.idle-timeout = 1s
     """)
    .withFallback(ConfigFactory.load())
}

class GenericRuntimeSpec
    extends TestKit(ActorSystem("test", GenericRuntimeSpec.conf))
    with AnyFunSuiteLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  implicit val ioRuntime: IORuntime = IORuntime.global

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  def runCounters(name: String): Resource[IO, CounterId => Counter[IO]] =
    GenericAkkaRuntime(system)
      .runBehavior[CounterId, Counter, IO](name, (_: CounterId) => Counter.inmem[IO])

  test("routing") {
    val program =
      runCounters("CounterFoo").use { counters =>
        val first = counters(CounterId("1"))
        val second = counters(CounterId("2"))

        for {
          _ <- first.increment
          _2 <- second.increment
          _1 <- first.increment
        } yield (_1, _2)
      }

    val (first, second) = program.unsafeRunSync()
    first shouldBe 2L
    second shouldBe 1L
  }

  test("passivation") {
    val program =
      runCounters("CounterBar").use { counters =>
        val first = counters(CounterId("1"))

        for {
          _1 <- first.increment
          afterPassivation <- IO.sleep(2.seconds) >> first.value
        } yield (_1, afterPassivation)
      }

    val (beforePassivation, afterPassivation) = program.unsafeRunSync()
    beforePassivation shouldBe 1
    afterPassivation shouldBe 0
  }
}
