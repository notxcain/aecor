package aecor.example

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import aecor.streaming.{ CassandraOffsetStore, ConsumerId }
import akka.actor.ActorSystem
import akka.persistence.cassandra.{
  CassandraSessionInitSerialization,
  DefaultJournalCassandraSession
}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import cats.data.Reader

import scala.concurrent.duration._
object ScheduleApp extends App {

  implicit val system = ActorSystem("aecor-example")
  implicit val materializer = ActorMaterializer()

  import materializer.executionContext

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
  val offsetStore = CassandraOffsetStore(cassandraSession, offsetStoreConfig)
  val scheduleEntryRepository =
    new CassandraScheduleEntryRepository(cassandraSession, scheduleEntryRepositoryQueries)

  val clock = Clock.systemDefaultZone()

  def runSchedule: Reader[Unit, Schedule] =
    Reader { _ =>
      Schedule(
        system,
        "Schedule",
        clock,
        LocalDate.of(2017, 2, 10),
        30.seconds,
        2.seconds,
        offsetStore,
        scheduleEntryRepository
      )
    }

  def runAdder(schedule: Schedule): Reader[Unit, Any] =
    Reader { _ =>
      Source
        .tick(0.seconds, 2.seconds, ())
        .mapAsync(8) { _ =>
          schedule.addScheduleEntry(
            "Test",
            UUID.randomUUID().toString,
            "test",
            LocalDateTime.now(clock).plusSeconds(3)
          )
        }
        .runWith(Sink.ignore)
    }

  def runEventWatch(schedule: Schedule): Reader[Unit, Any] =
    Reader { _ =>
      schedule
        .committableScheduleEvents("Test", ConsumerId("println"))
        .mapAsync(1) { x =>
          println(x.value)
          x.commit()
        }
        .runWith(Sink.ignore)
    }

  val app: Reader[Unit, Unit] =
    for {
      schedule <- runSchedule
      _ <- runAdder(schedule)
      _ <- runEventWatch(schedule)
    } yield ()

  app.run(())

}
