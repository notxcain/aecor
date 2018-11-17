package aecor.example

import java.time.{ Clock, LocalDate }
import java.util.UUID

import aecor.data.ConsumerId
import aecor.distributedprocessing.{ AkkaStreamProcess, DistributedProcessing }
import aecor.runtime.akkapersistence.readside.CassandraOffsetStore
import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import aecor.util.JavaTimeClock
import aecor.util.effect._
import akka.actor.ActorSystem
import akka.persistence.cassandra.DefaultJournalCassandraSession
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import cats.effect._
import cats.implicits._

import scala.concurrent.duration._

object ScheduleApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = IO.suspend {
    implicit val system = ActorSystem("test")
    implicit val materializer = ActorMaterializer()

    def clock[F[_]: Sync] = JavaTimeClock[F](Clock.systemUTC())

    val offsetStoreConfig = CassandraOffsetStore.Queries("aecor_example")
    val scheduleEntryRepositoryQueries =
      CassandraScheduleEntryRepository.Queries("aecor_example", "schedule_entries")
    def createCassandraSession[F[_]: Effect] = DefaultJournalCassandraSession[F](
      system,
      "App",
      CassandraOffsetStore[F].createTable(offsetStoreConfig) >>
        CassandraScheduleEntryRepository.init[F](scheduleEntryRepositoryQueries)
    )

    def runSchedule[F[_]: Effect](cassandraSession: CassandraSession) =
      Schedule.start(
        entityName = "Schedule",
        dayZero = LocalDate.of(2016, 5, 10),
        clock = clock,
        repository =
          CassandraScheduleEntryRepository[F](cassandraSession, scheduleEntryRepositoryQueries),
        offsetStore = CassandraOffsetStore[F](cassandraSession, offsetStoreConfig)
      )

    def runAdder[F[_]: Effect](schedule: Schedule[F]): F[Unit] =
      Effect[F].delay {
        Source
          .tick(0.seconds, 2.seconds, ())
          .mapAsync(1) { _ =>
            clock[F].localDateTime
              .flatMap { now =>
                schedule
                  .addScheduleEntry("Test", UUID.randomUUID().toString, "test", now.plusSeconds(20))
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
        session <- createCassandraSession
        schedule <- runSchedule[F](session)
        _ <- runAdder[F](schedule)
        _ <- runEventWatch[F](schedule)
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
      x <- timer.sleep(10.seconds) >> killswtich.shutdown
      _ <- {
        system.log.info(s"$x")
        app
      }
    } yield ExitCode.Success
  }

}
