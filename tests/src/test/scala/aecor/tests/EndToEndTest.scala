package aecor.tests

import java.time._

import aecor.data._
import aecor.effect.Capture
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule._
import aecor.schedule.process.{ ScheduleEventJournal, ScheduleProcess }
import aecor.testkit.StateEventJournal.State
import aecor.testkit.{ E2eSupport, StateClock, StateEventJournal, StateKeyValueStore }
import aecor.tests.e2e.CounterOp.{ Decrement, Increment }
import aecor.tests.e2e.TestCounterViewRepository.TestCounterViewRepositoryState
import aecor.tests.e2e._
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationId }
import cats.data.StateT
import cats.implicits._
import org.scalatest.{ FunSuite, Matchers }
import shapeless.Coproduct

import scala.concurrent.duration._

class EndToEndTest extends FunSuite with Matchers with E2eSupport {

  def instant[F[_]: Capture]: F[Instant] =
    Capture[F].capture(Instant.ofEpochMilli(System.currentTimeMillis()))

  case class SpecState(
    counterJournalState: StateEventJournal.State[CounterId, CounterEvent],
    notificationJournalState: StateEventJournal.State[NotificationId, NotificationEvent],
    scheduleJournalState: StateEventJournal.State[ScheduleBucketId, ScheduleEvent],
    counterViewState: TestCounterViewRepositoryState,
    time: Instant,
    scheduleEntries: Vector[ScheduleEntry],
    offsetStoreState: Map[TagConsumer, LocalDateTime]
  )

  val offsetStore =
    StateKeyValueStore[SpecF, SpecState, TagConsumer, LocalDateTime](
      _.offsetStoreState,
      (s, os) => s.copy(offsetStoreState = os)
    )

  val clock = StateClock[SpecF, SpecState](ZoneOffset.UTC, _.time, (s, t) => s.copy(time = t))

  def counterEventJournal =
    mkJournal[CounterId, CounterEvent](
      _.counterJournalState,
      (x, a) => x.copy(counterJournalState = a)
    )

  def counterBehavior =
    mkBehavior[CounterId, CounterOp, CounterState, CounterEvent](
      CounterOpHandler.behavior[StateT[SpecF, SpecState, ?]],
      Tagging.const(CounterEvent.tag),
      counterEventJournal
    )

  def notificationEventJournal =
    mkJournal[NotificationId, NotificationEvent](
      _.notificationJournalState,
      (x, a) => x.copy(notificationJournalState = a)
    )

  def notificationBehavior =
    mkBehavior(
      notification.behavior,
      Tagging.const(NotificationEvent.tag),
      notificationEventJournal
    )

  def schduleEventJournal =
    mkJournal[ScheduleBucketId, ScheduleEvent](
      _.scheduleJournalState,
      (x, a) => x.copy(scheduleJournalState = a)
    )

  val scheduleAggregate = mkBehavior[ScheduleBucketId, ScheduleOp, ScheduleState, ScheduleEvent](
    DefaultScheduleBucket.behavior(clock.zonedDateTime),
    Tagging.const(EventTag("Schedule")),
    schduleEventJournal
  )

  val scheduleEntryRepository = TestScheduleEntryRepository[SpecF, SpecState](
    _.scheduleEntries,
    (x, a) => x.copy(scheduleEntries = a)
  )

  val scheduleProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")
  val wrappedEventJournal = new ScheduleEventJournal[StateT[SpecF, SpecState, ?]] {
    override def processNewEvents(
      f: Identified[ScheduleBucketId, ScheduleEvent] => StateT[SpecF, SpecState, Unit]
    ): StateT[SpecF, SpecState, Unit] =
      schduleEventJournal
        .eventsByTag(EventTag("Schedule"), scheduleProcessConsumerId)
        .process(f)
  }

  val scheduleProcess = ScheduleProcess[StateT[SpecF, SpecState, ?]](
    journal = wrappedEventJournal,
    dayZero = LocalDate.now(),
    consumerId = scheduleProcessConsumerId,
    offsetStore = offsetStore,
    eventualConsistencyDelay = 1.second,
    repository = scheduleEntryRepository,
    buckets = scheduleAggregate.andThen(ScheduleBucket.fromFunctionK),
    clock = clock.localDateTime,
    parallelism = 1
  )

  val counterViewProcessConsumerId: ConsumerId = ConsumerId("CounterViewProcess")

  val notificationProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")

  override def otherStuff: Vector[StateT[SpecF, SpecState, Unit]] =
    Vector(scheduleProcess)

  override def processes: Vector[WiredProcess[StateT[SpecF, SpecState, ?]]] = Vector(
    wireProcess(
      CounterViewProcess(
        TestCounterViewRepository[SpecF, SpecState](
          _.counterViewState,
          (x, a) => x.copy(counterViewState = a)
        ),
        counterBehavior
      ),
      counterEventJournal
        .eventsByTag(CounterEvent.tag, counterViewProcessConsumerId)
    ),
    wireProcess(
      NotificationProcess(counterBehavior, notificationBehavior),
      counterEventJournal
        .eventsByTag(CounterEvent.tag, notificationProcessConsumerId)
        .map(Coproduct[NotificationProcess.Input](_)),
      notificationEventJournal
        .eventsByTag(NotificationEvent.tag, notificationProcessConsumerId)
        .map(Coproduct[NotificationProcess.Input](_))
    )
  )

  def tickSeconds(seconds: Long) = wired(clock.tick)(java.time.Duration.ofSeconds(seconds))

  test("Process should react to events") {

    val counter = wiredK(counterBehavior)

    val first = CounterId("1")
    val second = CounterId("2")

    val program = for {
      _ <- counter(first)(Increment)
      _ <- counter(first)(Increment)
      _ <- counter(first)(Decrement)
      _ <- counter(second)(Increment)
      _ <- counter(second)(Increment)
    } yield ()

    val Right((state, _)) = program
      .run(
        SpecState(
          State.init,
          State.init,
          State.init,
          TestCounterViewRepositoryState.init,
          Instant.now(),
          Vector.empty,
          Map.empty
        )
      )
      .value
      .value

    state.counterViewState.value shouldBe Map(first -> 1L, second -> 2L)

    state.notificationJournalState.eventsById
      .getOrElse("1-2", Vector.empty) should have size (2)
  }

  test("Schedule should fire") {

    val buckets = wiredK(scheduleAggregate)

    def program(n: Int): StateT[SpecF, SpecState, Unit] =
      for {
        now <- clock.localDateTime
        bucketId = ScheduleBucketId("foo", "b")
        bucket = buckets(bucketId)
        _ <- bucket(ScheduleOp.AddScheduleEntry("e1", "cid", now.plusSeconds(3)))
        _ <- bucket(ScheduleOp.AddScheduleEntry("e2", "cid", now.plusSeconds(5)))
        _ <- tickSeconds(3)
        _ <- tickSeconds(2)
        _ <- if (n == 0) {
              ().pure[StateT[SpecF, SpecState, ?]]
            } else {
              program(n - 1)
            }
      } yield ()

    val Right((state, _)) = program(100)
      .run(
        SpecState(
          State.init,
          State.init,
          State.init,
          TestCounterViewRepositoryState.init,
          Instant.now(Clock.systemUTC()),
          Vector.empty,
          Map.empty
        )
      )
      .value
      .value

    state.scheduleEntries.exists(e => e.entryId == "e1" && e.fired) shouldBe true
    state.scheduleEntries.exists(e => e.entryId == "e2" && e.fired) shouldBe true
  }
}
