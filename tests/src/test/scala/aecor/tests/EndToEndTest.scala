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
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationOp }
import cats.data.StateT
import cats.implicits._
import org.scalatest.{ FunSuite, Matchers }
import shapeless.Coproduct

import scala.concurrent.duration._

class EndToEndTest extends FunSuite with Matchers with E2eSupport {

  def instant[F[_]: Capture]: F[Instant] =
    Capture[F].capture(Instant.ofEpochMilli(System.currentTimeMillis()))

  case class SpecState(counterJournalState: StateEventJournal.State[CounterEvent],
                       notificationJournalState: StateEventJournal.State[NotificationEvent],
                       scheduleJournalState: StateEventJournal.State[ScheduleEvent],
                       counterViewState: TestCounterViewRepositoryState,
                       time: Instant,
                       scheduleEntries: Vector[ScheduleEntry],
                       offsetStoreState: Map[TagConsumer, LocalDateTime])

  val offsetStore =
    StateKeyValueStore[SpecF, SpecState, TagConsumer, LocalDateTime](
      _.offsetStoreState,
      (s, os) => s.copy(offsetStoreState = os)
    )

  val clock = StateClock[SpecF, SpecState](ZoneOffset.UTC, _.time, (s, t) => s.copy(time = t))

  def counterEventJournal =
    mkJournal[CounterEvent](_.counterJournalState, (x, a) => x.copy(counterJournalState = a))

  def counterBehavior =
    mkBehavior[CounterOp, CounterState, CounterEvent](
      CounterOpHandler.behavior[StateT[SpecF, SpecState, ?]],
      _.id,
      Tagging.const(CounterEvent.tag),
      counterEventJournal
    )

  def notificationEventJournal =
    mkJournal[NotificationEvent](
      _.notificationJournalState,
      (x, a) => x.copy(notificationJournalState = a)
    )

  def notificationBehavior =
    mkBehavior(
      notification.behavior,
      NotificationOp.correlation,
      Tagging.const(NotificationEvent.tag),
      notificationEventJournal
    )

  def schduleEventJournal =
    mkJournal[ScheduleEvent](_.scheduleJournalState, (x, a) => x.copy(scheduleJournalState = a))

  val scheduleAggregate = mkBehavior[ScheduleOp, ScheduleState, ScheduleEvent](
    DefaultScheduleAggregate.behavior(clock.zonedDateTime),
    DefaultScheduleAggregate.correlation,
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
      f: (ScheduleEvent) => StateT[SpecF, SpecState, Unit]
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
    scheduleAggregate = ScheduleAggregate.fromFunctionK(scheduleAggregate),
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

    val program = for {
      _ <- counter(Increment("1"))
      _ <- counter(Increment("1"))
      _ <- counter(Decrement("1"))
      _ <- counter(Increment("2"))
      _ <- counter(Increment("2"))
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

    state.counterViewState.value shouldBe Map("1" -> 1L, "2" -> 2L)

    state.notificationJournalState.eventsById
      .getOrElse("1-2", Vector.empty) should have size (2)
  }

  test("Schedule should fire") {

    val schedule = wiredK(scheduleAggregate)

    def program(n: Int): StateT[SpecF, SpecState, Unit] =
      for {
        now <- clock.localDateTime
        _ <- schedule(ScheduleOp.AddScheduleEntry("foo", "b", "e1", "cid", now.plusSeconds(3)))
        _ <- schedule(ScheduleOp.AddScheduleEntry("foo", "b", "e2", "cid", now.plusSeconds(5)))
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
