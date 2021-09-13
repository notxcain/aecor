package aecor.example

import java.time.{ Clock, LocalDate }
import java.util.UUID

import aecor.data.ConsumerId
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.runtime.akkapersistence.readside.CassandraOffsetStore
import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import aecor.util.JavaTimeClock
import akka.actor.ActorSystem
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import cats.effect._
import cats.effect.std.Dispatcher
import cats.syntax.all._

import scala.concurrent.duration._

object ScheduleApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = IO.defer {
    implicit val system = ActorSystem("test")
    implicit val materializer = ActorMaterializer()

    def clock[F[_]: Sync] = JavaTimeClock[F](Clock.systemUTC())

    val offsetStoreConfig = CassandraOffsetStore.Queries("aecor_example")
    val scheduleEntryRepositoryQueries =
      CassandraScheduleEntryRepository.Queries("aecor_example", "schedule_entries")
    def createCassandraSession[F[_]: Async] = DefaultJournalCassandraSession[F](
      system,
      "App",
      CassandraOffsetStore[F].createTable(offsetStoreConfig) >>
        CassandraScheduleEntryRepository.init[F](scheduleEntryRepositoryQueries)
    )

    def runSchedule[F[_]: Async: LiftIO](cassandraSession: CassandraSession) =
      CassandraScheduleEntryRepository[F](cassandraSession, scheduleEntryRepositoryQueries).flatMap(
        scheduleEntryRepository =>
          Schedule.start(
            entityName = "Schedule",
            dayZero = LocalDate.of(2016, 5, 10),
            clock = clock,
            repository = scheduleEntryRepository,
            offsetStore = CassandraOffsetStore[F](cassandraSession, offsetStoreConfig)
        )
      )

    def runAdder[F[_]: Async](schedule: Schedule[F], dispatcher: Dispatcher[F]): F[Unit] =
      Async[F].delay {
        Source
          .tick(0.seconds, 2.seconds, ())
          .mapAsync(1) { _ =>
            dispatcher.unsafeToFuture(
              clock[F].localDateTime
                .flatMap { now =>
                  schedule
                    .addScheduleEntry(
                      "Test",
                      UUID.randomUUID().toString,
                      "test",
                      now.plusSeconds(20)
                    )
                }
            )
          }
          .runWith(Sink.ignore)
      }.void

    def runEventWatch[F[_]: Async](schedule: Schedule[F], dispatcher: Dispatcher[F]): F[Unit] =
      Async[F].delay {
        schedule
          .committableScheduleEvents("SubscriptionInvoicing", ConsumerId("println"))
          .mapAsync(1) { x =>
            println(x.value)
            dispatcher.unsafeToFuture(x.commit)
          }
          .runWith(Sink.ignore)
      }.void

    def mkApp[F[_]: Async: LiftIO]: F[Unit] =
      for {
        dispatcher <- Dispatcher[F].allocated.map(_._1)
        session <- createCassandraSession
        schedule <- runSchedule[F](session)
        _ <- runAdder[F](schedule, dispatcher)
        _ <- runEventWatch[F](schedule, dispatcher)
      } yield ()

    val app: IO[Unit] =
      mkApp[IO]

    val processes = (0 to 10).map { x =>
      AkkaStreamProcess[IO](
        Source.tick(0.seconds, 2.seconds, x).map(x => system.log.info(s"Worker $x"))
      )
    }

    val distributed = DistributedProcessing(system)
      .start[IO]("TestProcesses", processes.toList)

    for {
      killswtich <- distributed
      x <- IO.sleep(10.seconds) >> killswtich.shutdown
      _ <- {
        system.log.info(s"$x")
        app
      }
    } yield ExitCode.Success
  }

}
