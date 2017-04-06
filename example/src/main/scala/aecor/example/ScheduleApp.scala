package aecor.example

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.effect.{ Async, Capture, CaptureFuture }
import aecor.schedule.{ CassandraScheduleEntryRepository, Schedule }
import akka.actor.ActorSystem
import akka.persistence.cassandra.{
  CassandraSessionInitSerialization,
  DefaultJournalCassandraSession
}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.data.{ EitherT, Kleisli }
import cats.implicits._
import cats.{ Functor, MonadError }
import Async.ops._
import aecor.data.EventsourcedBehavior
import aecor.runtime.akkapersistence.{ CassandraAggregateJournal, CassandraOffsetStore }
import aecor.streaming.ConsumerId
import aecor.streaming.process.{ DistributedProcessing, StreamingProcess }

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

  def runSchedule[F[_]: Async: CaptureFuture: Capture: MonadError[
    ?[_],
    EventsourcedBehavior.BehaviorFailure
  ]]: F[Schedule[F]] =
    Schedule.start(
      entityName = "Schedule",
      clock = clock,
      dayZero = LocalDate.of(2016, 5, 10),
      bucketLength = 1.day,
      refreshInterval = 100.millis,
      eventualConsistencyDelay = 5.seconds,
      repository =
        CassandraScheduleEntryRepository[F](cassandraSession, scheduleEntryRepositoryQueries),
      aggregateJournal = CassandraAggregateJournal(system),
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
          x.commit().unsafeRun
        }
        .runWith(Sink.ignore)
    }.void

  def mkApp[F[_]: Async: CaptureFuture: Capture: MonadError[?[_],
                                                            EventsourcedBehavior.BehaviorFailure]]
    : F[Unit] =
    for {
      schedule <- runSchedule[F]
      _ <- runAdder[F](schedule)
      _ <- runEventWatch[F](schedule)
    } yield ()

  val app: EitherT[Kleisli[Future, Unit, ?], EventsourcedBehavior.BehaviorFailure, Unit] =
    mkApp[EitherT[Kleisli[Future, Unit, ?], EventsourcedBehavior.BehaviorFailure, ?]]

  DistributedProcessing(system)
    .start[Kleisli[Future, Unit, ?]](
      "test",
      10,
      x =>
        StreamingProcess(Source.tick(0.seconds, 2.seconds, x).take(5), Flow[Int].map { x =>
          system.log.info(s"Worker $x")
        })
    )
    .run(())
}
