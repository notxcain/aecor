package aecor.example

import java.time.{ Clock, LocalDate, LocalDateTime }

import aecor.data.EventTag
import aecor.schedule.ScheduleEvent.ScheduleEntryAdded
import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule, ScheduleEvent }
import aecor.streaming.{ CassandraAggregateJournal, CassandraOffsetStore }
import akka.Done
import akka.actor.ActorSystem
import akka.persistence.cassandra.{
  CassandraSessionInitSerialization,
  DefaultJournalCassandraSession
}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import cats.data.Reader

import scala.concurrent.Future
import scala.concurrent.duration._
object ScheduleApp extends App {

  implicit val system = ActorSystem("aecor-example")
  implicit val materializer = ActorMaterializer()

  val clock = Clock.systemDefaultZone()

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
        .runForeach(println)
    }

  def runSchedule: Reader[Unit, Schedule] =
    Reader { _ =>
      Schedule(
        system = system,
        entityName = "Schedule",
        clock = clock,
        dayZero = LocalDate.of(2016, 5, 10),
        bucketLength = 1.day,
        refreshInterval = 1.second,
        offsetStore = offsetStore,
        repository = scheduleEntryRepository
      )
    }

  def runEventWatch: Reader[Unit, Future[Done]] =
    Reader { _ =>
      CassandraAggregateJournal(system)
        .eventsByTag(EventTag[ScheduleEvent]("Schedule"), None)
        .scan(0) { (counter, in) =>
          println(s"$counter => $in")
          counter + 1
        }
        .runWith(Sink.ignore)
    }

  val app: Reader[Unit, Unit] =
    for {
      _ <- runSchedule
//      _ <- runRepositoryScanStream
//      _ <- runEventWatch
    } yield ()

  app.run(())
}
