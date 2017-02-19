package aecor.example

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import aecor.streaming.{ CassandraAggregateJournal, CassandraOffsetStore, ConsumerId }
import akka.Done
import akka.actor.ActorSystem
import akka.persistence.cassandra.{
  CassandraSessionInitSerialization,
  DefaultJournalCassandraSession
}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import cats.data.Reader

import scala.concurrent.Future
import scala.concurrent.duration._
object ScheduleApp extends App {

  implicit val system = ActorSystem("aecor-example")
  implicit val materializer = ActorMaterializer()

  val clock = Clock.systemUTC()

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
    CassandraScheduleEntryRepository(cassandraSession, scheduleEntryRepositoryQueries)

  def runSchedule: Reader[Unit, Schedule] =
    Schedule.start(
      entityName = "Schedule",
      clock = clock,
      dayZero = LocalDate.of(2016, 5, 10),
      bucketLength = 1.day,
      refreshInterval = 1.second,
      eventualConsistencyDelay = 5.seconds,
      repository = scheduleEntryRepository,
      aggregateJournal = CassandraAggregateJournal(system),
      offsetStore = offsetStore
    )

  def runAdder(schedule: Schedule): Reader[Unit, Any] =
    Reader { _ =>
      Source
        .tick(0.seconds, 2.seconds, ())
        .mapAsync(1) { _ =>
          schedule.addScheduleEntry(
            "Test",
            UUID.randomUUID().toString,
            "test",
            LocalDateTime.now(clock).plusSeconds(20)
          )
        }
        .runWith(Sink.ignore)
    }

  def runEventWatch(schedule: Schedule): Reader[Unit, Future[Done]] =
    Reader { _ =>
      schedule
        .committableScheduleEvents("SubscriptionInvoicing", ConsumerId("println"))
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
//      _ <- runRepositoryScanStream
      _ <- runEventWatch(schedule)
    } yield ()

  app.run(())
}
