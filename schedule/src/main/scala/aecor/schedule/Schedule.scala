package aecor.schedule

import java.time.{ Clock, LocalDate, LocalDateTime }
import java.util.UUID

import aecor.aggregate.runtime.RuntimeActor.InstanceIdentity
import aecor.aggregate.runtime.behavior.Behavior
import aecor.aggregate.runtime.{ EventsourcedBehavior, GenericAkkaRuntime, NoopSnapshotStore }
import aecor.aggregate.{ CorrelationId, Tagging }
import aecor.data.EventTag
import aecor.streaming._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.cassandra.journal.CassandraEventJournal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.MonadError
import cats.data.Reader

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

trait Schedule {
  def addScheduleEntry(scheduleName: String,
                       entryId: String,
                       correlationId: CorrelationId,
                       dueDate: LocalDateTime): Future[Unit]
  def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[JournalEntry[UUID, ScheduleEvent]], NotUsed]
}

private[schedule] class ConfiguredSchedule(
  system: ActorSystem,
  entityName: String,
  clock: Clock,
  dayZero: LocalDate,
  bucketLength: FiniteDuration,
  tickInterval: FiniteDuration,
  eventualConsistencyDelay: FiniteDuration,
  repository: ScheduleEntryRepository,
  aggregateJournal: AggregateJournal[UUID],
  offsetStore: OffsetStore[UUID],
  consumerId: ConsumerId
)(implicit materializer: Materializer) {

  import materializer.executionContext

  private val runtime = new GenericAkkaRuntime(system)

  private val eventTag = EventTag[ScheduleEvent](entityName)

  implicit def futureMonadError(implicit ec: ExecutionContext): MonadError[Future, String] =
    new MonadError[Future, String] {
      override def raiseError[A](e: String): Future[A] = Future.failed(new RuntimeException(e))

      override def handleErrorWith[A](fa: Future[A])(f: (String) => Future[A]): Future[A] =
        cats.instances.future.catsStdInstancesForFuture.handleErrorWith(fa) { e =>
          f(e.getMessage)
        }

      override def flatMap[A, B](fa: Future[A])(f: (A) => Future[B]): Future[B] =
        fa.flatMap(f)

      override def tailRecM[A, B](a: A)(f: (A) => Future[Either[A, B]]): Future[B] =
        cats.instances.future.catsStdInstancesForFuture.tailRecM(a)(f)

      override def pure[A](x: A): Future[A] =
        Future.successful(x)
    }

  private val startAggregate = Reader { _: Unit =>
    val behavior: UUID => Future[Behavior[ScheduleCommand, Future]] =
      EventsourcedBehavior(
        entityName,
        DefaultScheduleAggregate.correlation,
        DefaultScheduleAggregate(clock).asFunctionK,
        Tagging(eventTag),
        CassandraEventJournal[ScheduleEvent, Future](system, 8),
        NoopSnapshotStore[ScheduleState, Future]
      )
    ScheduleAggregate.fromFunctionK(
      runtime.start(entityName, DefaultScheduleAggregate.correlation, behavior)
    )
  }

  private def startProcess(aggregate: ScheduleAggregate[Future]) =
    ScheduleProcess(
      clock = clock,
      entityName = entityName,
      consumerId = consumerId,
      dayZero = dayZero,
      refreshInterval = 1.second,
      eventualConsistencyDelay = eventualConsistencyDelay,
      parallelism = 8,
      offsetStore = offsetStore,
      repository = repository,
      scheduleAggregate = aggregate,
      aggregateJournal = aggregateJournal,
      eventTag = eventTag
    ).run(system)

  private def createSchedule(aggregate: ScheduleAggregate[Future]): Schedule =
    new DefaultSchedule(clock, aggregate, bucketLength, aggregateJournal, offsetStore, eventTag)

  def start: Reader[Unit, Schedule] =
    for {
      aggregate <- startAggregate
      _ <- startProcess(aggregate)
      schedule = createSchedule(aggregate)
    } yield schedule
}

object Schedule {
  def start(
    entityName: String,
    clock: Clock,
    dayZero: LocalDate,
    bucketLength: FiniteDuration,
    refreshInterval: FiniteDuration,
    eventualConsistencyDelay: FiniteDuration,
    repository: ScheduleEntryRepository,
    aggregateJournal: AggregateJournal[UUID],
    offsetStore: OffsetStore[UUID],
    consumerId: ConsumerId = ConsumerId("io.aecor.schedule.ScheduleProcess")
  )(implicit system: ActorSystem, materializer: Materializer): Reader[Unit, Schedule] =
    new ConfiguredSchedule(
      system,
      entityName,
      clock,
      dayZero,
      bucketLength,
      refreshInterval,
      eventualConsistencyDelay,
      repository,
      aggregateJournal,
      offsetStore,
      consumerId
    ).start
}
