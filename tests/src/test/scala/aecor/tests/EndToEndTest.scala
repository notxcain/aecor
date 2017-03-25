package aecor.tests

import java.time._

import aecor.aggregate.Tagging
import aecor.aggregate.runtime.Capture
import aecor.data.EventTag
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule._
import aecor.schedule.process.{ ScheduleProcess, ScheduleProcessOps }
import aecor.streaming.ConsumerId
import aecor.tests.e2e.CounterOp.{ Decrement, Increment }
import aecor.tests.e2e.TestCounterViewRepository.TestCounterViewRepositoryState
import aecor.tests.e2e.TestEventJournal.TestEventJournalState
import aecor.tests.e2e._
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationOp, notificationOpHandler }
import cats.data.StateT
import cats.implicits._
import cats.~>
import org.scalatest.{ FunSuite, Matchers }
import shapeless.Coproduct

import scala.concurrent.duration._

class EndToEndTest extends FunSuite with Matchers with E2eSupport {

  def instant[F[_]: Capture]: F[Instant] =
    Capture[F].capture(Instant.ofEpochMilli(System.currentTimeMillis()))

  case class SpecState(counterJournalState: TestEventJournalState[CounterEvent],
                       notificationJournalState: TestEventJournalState[NotificationEvent],
                       scheduleJournalState: TestEventJournalState[ScheduleEvent],
                       counterViewState: TestCounterViewRepositoryState,
                       time: Instant,
                       scheduleEntries: Vector[ScheduleEntry],
                       entriesOffset: LocalDateTime)

  val clock = StateClock[SpecF, SpecState](_.time, (s, t) => s.copy(time = t))

  def counterEventJournal =
    mkJournal[CounterEvent](_.counterJournalState, (x, a) => x.copy(counterJournalState = a))

  def counterBehavior: ~>[CounterOp, StateT[SpecF, SpecState, ?]] =
    mkBehavior(
      "Counter",
      CounterOp.correlation,
      new CounterOpHandler[StateT[SpecF, SpecState, ?]],
      Tagging(CounterEvent.tag),
      counterEventJournal
    )

  def notificationEventJournal =
    mkJournal[NotificationEvent](
      _.notificationJournalState,
      (x, a) => x.copy(notificationJournalState = a)
    )

  def notificationBehavior =
    mkBehavior(
      "Notification",
      NotificationOp.correlation,
      notificationOpHandler,
      Tagging(NotificationEvent.tag),
      notificationEventJournal
    )

  def schduleEventJournal =
    mkJournal[ScheduleEvent](_.scheduleJournalState, (x, a) => x.copy(scheduleJournalState = a))

  val scheduleAggregate = mkBehavior(
    "Schedule",
    DefaultScheduleAggregate.correlation,
    DefaultScheduleAggregate(clock.zonedDateTime(ZoneOffset.UTC)).asFunctionK,
    Tagging(EventTag[ScheduleEvent]("Schedule")),
    schduleEventJournal
  )

  val scheduleEntryRepository = TestScheduleEntryRepository[SpecF, SpecState](
    _.scheduleEntries,
    (x, a) => x.copy(scheduleEntries = a)
  )

  val scheduleProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")
  val ops = new ScheduleProcessOps[StateT[SpecF, SpecState, ?]] {
    override def processNewEvents(
      f: (ScheduleEvent) => StateT[SpecF, SpecState, Unit]
    ): StateT[SpecF, SpecState, Unit] =
      schduleEventJournal
        .eventsByTag(EventTag[ScheduleEvent]("Schedule"), scheduleProcessConsumerId)
        .process(f)

    override def processEntries(from: LocalDateTime, to: LocalDateTime)(
      f: (ScheduleEntryRepository.ScheduleEntry) => StateT[SpecF, SpecState, Unit]
    ): StateT[SpecF, SpecState, Option[ScheduleEntryRepository.ScheduleEntry]] =
      scheduleEntryRepository.processEntries(from, to, 8)(f)

    override def now: StateT[SpecF, SpecState, LocalDateTime] =
      clock.localDateTime(ZoneOffset.UTC)

    override def saveOffset(value: LocalDateTime): StateT[SpecF, SpecState, Unit] =
      StateT.modify(_.copy(entriesOffset = value))
    override def loadOffset: StateT[SpecF, SpecState, LocalDateTime] =
      StateT.inspect(_.entriesOffset)
  }

  val scheduleProcess = ScheduleProcess[StateT[SpecF, SpecState, ?]](
    ops,
    1.second,
    scheduleEntryRepository,
    ScheduleAggregate.fromFunctionK(scheduleAggregate)
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

    val (state, _) = program
      .run(
        SpecState(
          TestEventJournalState.init,
          TestEventJournalState.init,
          TestEventJournalState.init,
          TestCounterViewRepositoryState.init,
          Instant.now(),
          Vector.empty,
          LocalDateTime.now()
        )
      )
      .right
      .get

    state.counterViewState.value shouldBe Map("1" -> 1L, "2" -> 2L)
    state.notificationJournalState.eventsById
      .getOrElse("Notification-1-2", Vector.empty) should have size (2)
  }

  test("Schedule should fire") {

    val schedule = wiredK(scheduleAggregate)

    val program = for {
      now <- clock.localDateTime(ZoneOffset.UTC)
      _ <- schedule(ScheduleOp.AddScheduleEntry("foo", "b", "e1", "cid", now.plusSeconds(3)))
      _ <- schedule(ScheduleOp.AddScheduleEntry("foo", "b", "e2", "cid", now.plusSeconds(5)))
      _ <- tickSeconds(3)
      _ <- tickSeconds(2)
    } yield ()

    val (state, _) = program
      .run(
        SpecState(
          TestEventJournalState.init,
          TestEventJournalState.init,
          TestEventJournalState.init,
          TestCounterViewRepositoryState.init,
          Instant.now(Clock.systemUTC()),
          Vector.empty,
          LocalDateTime.now(Clock.systemUTC())
        )
      )
      .right
      .get

    println(state.scheduleJournalState.eventsByTag(EventTag("Schedule")).map(_.event))
    state.scheduleEntries.exists(e => e.entryId == "e1" && e.fired) shouldBe true
    state.scheduleEntries.exists(e => e.entryId == "e2" && e.fired) shouldBe true
  }
}
