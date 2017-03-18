package aecor.example

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.aggregate.runtime.{ Async, Capture, CaptureFuture, EventsourcedBehavior }
import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import aecor.streaming.{ CassandraAggregateJournal, CassandraOffsetStore, ConsumerId }
import akka.actor.ActorSystem
import akka.persistence.cassandra.{
  CassandraSessionInitSerialization,
  DefaultJournalCassandraSession
}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import cats.implicits._
import cats.{ Functor, MonadError }
import cats.data.{ EitherT, Kleisli }

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

  def runSchedule[F[_]: Async: CaptureFuture: Capture: MonadError[
    ?[_],
    EventsourcedBehavior.BehaviorFailure
  ]]: F[Schedule[F]] =
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

  def runEventWatch[F[_]: Capture: Functor](schedule: Schedule[F]): F[Unit] =
    Capture[F].capture {
      schedule
        .committableScheduleEvents("SubscriptionInvoicing", ConsumerId("println"))
        .mapAsync(1) { x =>
          println(x.value)
          x.commit()
        }
        .runWith(Sink.ignore)
    }.void

  def mkApp[F[_]: Async: CaptureFuture: Capture: MonadError[?[_], String]]: F[Unit] =
    for {
      schedule <- runSchedule[F]
      _ <- runAdder[F](schedule)
      _ <- runEventWatch[F](schedule)
    } yield ()

  val app: EitherT[Kleisli[Future, Unit, ?], String, Unit] =
    mkApp[EitherT[Kleisli[Future, Unit, ?], String, ?]]

  app.value.run(())
}
