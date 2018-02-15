package aecor.tests

import aecor.data.Tagging
import aecor.tests.e2e._
import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ Config, ConfigFactory }
import monix.eval.Task
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FunSuiteLike, Matchers }
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntime, CassandraJournalAdapter }
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

  override def systemName = system.name

  override implicit val patienceConfig = PatienceConfig(15.seconds, 150.millis)

  implicit val scheduler = Scheduler(system.dispatcher)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  val runtime = AkkaPersistenceRuntime(system, CassandraJournalAdapter(system))

  test("Runtime should work") {
    val deployCounters: Task[CounterId => Counter[Task]] =
      runtime.deploy(
        "Counter",
        CounterBehavior.instance.lifted[Task],
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
      afterPassivation <- second.value.delayExecution(2.seconds)
    } yield (_1, _2, afterPassivation)

    program.runAsync.futureValue shouldEqual ((0L, 1L, 1L))
  }
  test("Journal should work") {
    implicit val materializer = ActorMaterializer()
    val journal = runtime.journal[CounterId, CounterEvent]
    val events = journal.currentEventsByTag(CounterEvent.tag, None).runWith(Sink.seq).futureValue

    val map = events.groupBy(_.entityId)
    println(map)
    map(CounterId("1")).size shouldBe 2
    map(CounterId("2")).size shouldBe 1
  }
}
