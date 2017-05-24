package aecor.example

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.data.ConsumerId
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.effect.Async.ops._
import aecor.effect.monix._
import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.experimental.Eventsourced
import aecor.runtime.akkapersistence.{ CassandraEventJournalQuery, CassandraOffsetStore }
import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.{
  CassandraSessionInitSerialization,
  DefaultJournalCassandraSession
}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.data.EitherT
import cats.implicits._
import cats.{ Functor, Monad, MonadError }
import monix.eval.Task
import monix.execution.Scheduler
import monix.cats._
import scala.concurrent.Await
import scala.concurrent.duration._
object ScheduleApp extends App {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorMaterializer()
  implicit val scheduler = Scheduler(materializer.executionContext)
  val clock = Clock.systemUTC()

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

  def runSchedule[F[_]: Async: CaptureFuture: Capture: Monad]: F[Schedule[F]] =
    Schedule.start(
      entityName = "Schedule",
      clock = clock,
      dayZero = LocalDate.of(2016, 5, 10),
      repository =
        CassandraScheduleEntryRepository[F](cassandraSession, scheduleEntryRepositoryQueries),
      aggregateJournal = CassandraEventJournalQuery(system),
      offsetStore = CassandraOffsetStore(cassandraSession, offsetStoreConfig)
    )

  def runAdder[F[_]: Async: Capture: Functor](schedule: Schedule[F]): F[Unit] =
    Capture[F].capture {
      Source
        .tick(0.seconds, 2.seconds, ())
        .mapAsync(1) { _ =>
          Async[F].unsafeRun {
            schedule.addScheduleEntry(
              "Test",
              UUID.randomUUID().toString,
              "test",
              LocalDateTime.now(clock).plusSeconds(20)
            )
          }
        }
        .runWith(Sink.ignore)
    }.void

  def runEventWatch[F[_]: Async: Capture: Functor](schedule: Schedule[F]): F[Unit] =
    Capture[F].capture {
      schedule
        .committableScheduleEvents("SubscriptionInvoicing", ConsumerId("println"))
        .mapAsync(1) { x =>
          println(x.value)
          x.commit.unsafeRun
        }
        .runWith(Sink.ignore)
    }.void

  def mkApp[F[_]: Async: CaptureFuture: Capture: MonadError[?[_], Eventsourced.BehaviorFailure]]
    : F[Unit] =
    for {
      schedule <- runSchedule[F]
      _ <- runAdder[F](schedule)
      _ <- runEventWatch[F](schedule)
    } yield ()

  val app: EitherT[Task, Eventsourced.BehaviorFailure, Unit] =
    mkApp[EitherT[Task, Eventsourced.BehaviorFailure, ?]]

  val processes = DistributedProcessing.distribute[Task](10) { x =>
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

  Await.result(app.value.runAsync, Duration.Inf)
}
