package aecor.example

import java.time.{ Clock, LocalDate }
import java.util.UUID

import aecor.data.ConsumerId
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.runtime.akkapersistence.readside.CassandraOffsetStore
import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import aecor.util.JavaTimeClock
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.{
  CassandraSessionInitSerialization,
  DefaultJournalCassandraSession
}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.effect.{ Effect, Sync }
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import aecor.util.effect._

import scala.concurrent.Await
import scala.concurrent.duration._
object ScheduleApp extends App {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()
  implicit val scheduler = Scheduler(materializer.executionContext)
  def clock[F[_]: Sync] = JavaTimeClock[F](Clock.systemUTC())

  val offsetStoreConfig = CassandraOffsetStore.Config("aecor_example")
  val scheduleEntryRepositoryQueries =
    CassandraScheduleEntryRepository.Queries("aecor_example", "schedule_entries")
  val cassandraSession = DefaultJournalCassandraSession(
    system,
    "App",
    CassandraSessionInitSerialization.serialize(
      CassandraOffsetStore.createTable(offsetStoreConfig),
      CassandraScheduleEntryRepository.init(scheduleEntryRepositoryQueries)
    )
  )

  def runSchedule[F[_]: Effect]: F[Schedule[F]] =
    Schedule.start(
      entityName = "Schedule",
      dayZero = LocalDate.of(2016, 5, 10),
      clock = clock,
      repository =
        CassandraScheduleEntryRepository[F](cassandraSession, scheduleEntryRepositoryQueries),
      offsetStore = CassandraOffsetStore(cassandraSession, offsetStoreConfig)
    )

  def runAdder[F[_]: Effect](schedule: Schedule[F]): F[Unit] =
    Effect[F].delay {
      Source
        .tick(0.seconds, 2.seconds, ())
        .mapAsync(1) { _ =>
          clock[F].localDateTime
            .flatMap { now =>
              schedule.addScheduleEntry(
                "Test",
                UUID.randomUUID().toString,
                "test",
                now.plusSeconds(20)
              )
            }
            .unsafeToFuture()

        }
        .runWith(Sink.ignore)
    }.void

  def runEventWatch[F[_]: Effect](schedule: Schedule[F]): F[Unit] =
    Effect[F].delay {
      schedule
        .committableScheduleEvents("SubscriptionInvoicing", ConsumerId("println"))
        .mapAsync(1) { x =>
          println(x.value)
          x.commit.unsafeToFuture()
        }
        .runWith(Sink.ignore)
    }.void

  def mkApp[F[_]: Effect]: F[Unit] =
    for {
      schedule <- runSchedule[F]
      _ <- runAdder[F](schedule)
      _ <- runEventWatch[F](schedule)
    } yield ()

  val app: Task[Unit] =
    mkApp[Task]

  val processes = (0 to 10).map { x =>
    AkkaStreamProcess[Task](
      Source.tick(0.seconds, 2.seconds, x).mapMaterializedValue(_ => NotUsed),
      Flow[Int].map { x =>
        system.log.info(s"Worker $x")
        ()
      }
    )
  }

  val distributed = DistributedProcessing(system)
    .start[Task]("TestProcesses", processes)

  val app2: Task[Unit] = for {
    killswtich <- distributed
    x <- killswtich.shutdown.delayExecution(10.seconds)
    _ <- {
      system.log.info(s"$x")
      app2
    }
  } yield ()

  Await.result(app.runAsync, Duration.Inf)
}
