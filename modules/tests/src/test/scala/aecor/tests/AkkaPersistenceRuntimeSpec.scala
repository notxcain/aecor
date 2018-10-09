package aecor.tests

import aecor.data.Tagging
import aecor.tests.e2e._
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSuiteLike, Matchers}
import aecor.runtime.akkapersistence.{AkkaPersistenceRuntime, CassandraJournalAdapter}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.implicits._
import scala.concurrent.duration._

object AkkaPersistenceRuntimeSpec {
  def conf: Config = ConfigFactory.parseString(s"""
        akka {
          cluster {
            seed-nodes = [
              "akka.tcp://test@127.0.0.1:52000"
            ]
          }
          actor.provider = cluster
          remote {
            netty.tcp {
              hostname = 127.0.0.1
              port = 52000
              bind.hostname = "0.0.0.0"
              bind.port = 52000
            }
          }
        }
        aecor.generic-akka-runtime.idle-timeout = 1s
     """).withFallback(CassandraLifecycle.config).withFallback(ConfigFactory.load())
}

class AkkaPersistenceRuntimeSpec
    extends TestKit(ActorSystem("test", AkkaPersistenceRuntimeSpec.conf))
    with FunSuiteLike
    with Matchers
    with ScalaFutures
    with CassandraLifecycle {

  override def systemName = system.name

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  val timer = IO.timer(system.dispatcher)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  val runtime = AkkaPersistenceRuntime(system, CassandraJournalAdapter(system))

  test("Runtime should work") {
    val deployCounters: IO[CounterId => Counter[IO]] =
      runtime.deploy(
        "Counter",
        CounterBehavior.instance[IO],
        Tagging.const[CounterId](CounterEvent.tag)
      )
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

    program.unsafeRunSync() shouldEqual ((0L, 1L, 1L))
  }
  test("Journal should work") {
    implicit val materializer = ActorMaterializer()
    val journal = runtime.journal[CounterId, CounterEvent]
    val entries = journal.currentEventsByTag(CounterEvent.tag, None).runWith(Sink.seq).futureValue

    val map = entries.map(_.event).groupBy(_.entityKey)
    map(CounterId("1")).size shouldBe 2
    map(CounterId("2")).size shouldBe 1
  }
}
