package aecor.tests

import java.time._

import aecor.data._
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule._
import aecor.schedule.process.{ ScheduleEventJournal, ScheduleProcess }
import aecor.testkit.{ E2eSupport, StateClock, StateEventJournal, StateKeyValueStore }
import aecor.tests.e2e._
import aecor.testkit.E2eSupport._
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationId }
import cats.implicits._
import org.scalatest.{ FunSuite, Matchers }
import aecor.testkit.Lens
import shapeless.Coproduct
import scala.concurrent.duration._
import monocle.Lens
import monocle.macros.GenLens

class EndToEndTest extends FunSuite with Matchers with E2eSupport {
  import cats.mtl.instances.all._

  case class SpecState(
    counterJournalState: StateEventJournal.State[CounterId, CounterEvent],
    notificationJournalState: StateEventJournal.State[NotificationId, NotificationEvent],
    scheduleJournalState: StateEventJournal.State[ScheduleBucketId, ScheduleEvent],
    counterViewState: TestCounterViewRepository.State,
    time: Instant,
    scheduleEntries: Vector[ScheduleEntry],
    offsetStoreState: Map[TagConsumer, LocalDateTime]
  )

  val clock = StateClock[F, SpecState](ZoneOffset.UTC, GenLens[SpecState](_.time))

  def counterEventJournal =
    mkJournal[CounterId, CounterEvent](
      GenLens[SpecState](_.counterJournalState),
      Tagging.const(CounterEvent.tag)
    )

  def counters =
    runtime.deploy(behavior(CounterBehavior.instance.lift[F], counterEventJournal))

  def notificationEventJournal =
    mkJournal[NotificationId, NotificationEvent](
      GenLens[SpecState](_.notificationJournalState),
      Tagging.const(NotificationEvent.tag)
    )

  def notifications =
    runtime.deploy(behavior(notification.behavior.lift[F], notificationEventJournal))

  def schduleEventJournal =
    mkJournal[ScheduleBucketId, ScheduleEvent](
      GenLens[SpecState](_.scheduleJournalState),
      Tagging.const(EventTag("Schedule"))
    )

  val scheduleBuckets = runtime.deploy(
    behavior(DefaultScheduleBucket.behavior(clock.zonedDateTime), schduleEventJournal)
  )

  val scheduleEntryRepository =
    TestScheduleEntryRepository[F, SpecState](GenLens[SpecState](_.scheduleEntries))

  val scheduleProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")
  val wrappedEventJournal = new ScheduleEventJournal[F] {
    override def processNewEvents(
      f: EntityEvent[ScheduleBucketId, ScheduleEvent] => F[Unit]
    ): F[Unit] =
      schduleEventJournal
        .currentEventsByTag(EventTag("Schedule"), scheduleProcessConsumerId)
        .process(f)
  }

  val offsetStore =
    StateKeyValueStore[F, SpecState, TagConsumer, LocalDateTime](
      Lens(_.offsetStoreState, (s, os) => s.copy(offsetStoreState = os))
    )

  val scheduleProcess = ScheduleProcess[F](
    journal = wrappedEventJournal,
    dayZero = LocalDate.now(),
    consumerId = scheduleProcessConsumerId,
    offsetStore = offsetStore,
    eventualConsistencyDelay = 1.second,
    repository = scheduleEntryRepository,
    buckets = scheduleBuckets,
    clock = clock.localDateTime,
    parallelism = 1
  )

  val counterViewProcessConsumerId: ConsumerId = ConsumerId("CounterViewProcess")

  val notificationProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")

  val processes = Processes[F, SpecState](
    wireProcess(
      CounterViewProcess(
        TestCounterViewRepository[F, SpecState](
          Lens(_.counterViewState, (x, a) => x.copy(counterViewState = a))
        )
      ),
      counterEventJournal
        .currentEventsByTag(CounterEvent.tag, counterViewProcessConsumerId)
    ),
    wireProcess(
      NotificationProcess(counters, notifications),
      counterEventJournal
        .currentEventsByTag(CounterEvent.tag, notificationProcessConsumerId)
        .map(Coproduct[NotificationProcess.Input](_)),
      notificationEventJournal
        .currentEventsByTag(NotificationEvent.tag, notificationProcessConsumerId)
        .map(Coproduct[NotificationProcess.Input](_))
    ),
    scheduleProcess
  )

  import processes._

  def tickSeconds(seconds: Long) = wired(clock.tick)(java.time.Duration.ofSeconds(seconds))

  test("Process should react to events") {

    val counter = wiredK(counters)

    val first = CounterId("1")
    val second = CounterId("2")

    val program = for {
      _ <- counter(first).increment
      _ <- counter(first).increment
      _ <- counter(first).decrement
      _ <- counter(second).increment
      _ <- counter(second).increment
    } yield ()

    val Right((state, _)) = program
      .run(
        SpecState(
          StateEventJournal.State.init,
          StateEventJournal.State.init,
          StateEventJournal.State.init,
          TestCounterViewRepository.State.init,
          Instant.now(),
          Vector.empty,
          Map.empty
        )
      )

    state.counterViewState.values shouldBe Map(first -> 1L, second -> 2L)

    state.notificationJournalState.eventsById
      .getOrElse("1-2", Vector.empty) should have size (2)
  }

  test("Schedule should fire") {

    val buckets = wiredK(scheduleBuckets)

    def program(n: Int): F[Unit] =
      for {
        now <- clock.localDateTime
        bucketId = ScheduleBucketId("foo", "b")
        bucket = buckets(bucketId)
        _ <- bucket.addScheduleEntry("e1", "cid", now.plusSeconds(3))
        _ <- bucket.addScheduleEntry("e2", "cid", now.plusSeconds(5))
        _ <- tickSeconds(3)
        _ <- tickSeconds(2)
        _ <- if (n == 0) {
              ().pure[F]
            } else {
              program(n - 1)
            }
      } yield ()

    val Right((state, _)) = program(100)
      .run(
        SpecState(
          StateEventJournal.State.init,
          StateEventJournal.State.init,
          StateEventJournal.State.init,
          TestCounterViewRepository.State.init,
          Instant.now(Clock.systemUTC()),
          Vector.empty,
          Map.empty
        )
      )

    state.scheduleEntries.exists(e => e.entryId == "e1" && e.fired) shouldBe true
    state.scheduleEntries.exists(e => e.entryId == "e2" && e.fired) shouldBe true
  }
}
