package aecor.example

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.schedule.ScheduleEvent.ScheduleEntryAdded
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

  case class Counter(state: Map[(String, String, String), Int]) {
    def inc(e: ScheduleEntryAdded): Counter = {
      val key = (e.scheduleName, e.scheduleBucket, e.entryId)
      copy(state = state.updated(key, state.getOrElse(key, 0) + 1))
    }
    def duplicated: Set[(String, String, String)] =
      state.collect {
        case (x, v) if v > 1 => x
      }.toSet
  }

  def runRepositoryScanStream: Reader[Unit, Future[Done]] =
    Reader { _ =>
      scheduleEntryRepository
        .getEntries(LocalDate.of(2017, 1, 1).atStartOfDay(), LocalDateTime.now(clock))
        .runForeach(x => system.log.debug(s"[$x]"))
    }

  def runSchedule: Reader[Unit, Schedule] =
    Schedule.start(
      entityName = "Schedule",
      clock = clock,
      dayZero = LocalDate.of(2016, 5, 10),
      bucketLength = 1.day,
      refreshInterval = 1.second,
      repository = scheduleEntryRepository,
      aggregateJournal = CassandraAggregateJournal(system),
      offsetStore = offsetStore
    )

  def runAdder(schedule: Schedule): Reader[Unit, Any] =
    Reader { _ =>
      Source
        .tick(0.seconds, 2.seconds, ())
        .take(1)
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
