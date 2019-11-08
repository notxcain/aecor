package aecor.tests

import java.time._

import aecor.data._
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.schedule._
import aecor.schedule.process.{ ScheduleEventJournal, ScheduleProcess }
import aecor.testkit.E2eSupport._
import aecor.testkit.{ E2eSupport, StateClock, StateEventJournal, StateKeyValueStore }
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationId }
import aecor.tests.e2e.{ notification, _ }
import cats.data.Chain
import cats.implicits._
import monocle.macros.GenLens
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite
import shapeless.Coproduct

import scala.concurrent.duration._

class EndToEndTest extends AnyFunSuite with Matchers with E2eSupport {
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
    mkJournal(GenLens[SpecState](_.counterJournalState), Tagging.const(CounterEvent.tag))

  def counters: CounterId => Counter[F] =
    runtime.deploy(CounterBehavior.instance[F], counterEventJournal)

  def notificationEventJournal =
    mkJournal(GenLens[SpecState](_.notificationJournalState), Tagging.const(NotificationEvent.tag))

  def notifications: NotificationId => notification.Notification[F] =
    runtime.deploy(notification.behavior[F], notificationEventJournal)

  def schduleEventJournal =
    mkJournal(GenLens[SpecState](_.scheduleJournalState), Tagging.const(EventTag("Schedule")))

  val scheduleBuckets =
    runtime.deploy(DefaultScheduleBucket.behavior(clock.zonedDateTime), schduleEventJournal)

  val scheduleEntryRepository =
    TestScheduleEntryRepository[F, SpecState](GenLens[SpecState](_.scheduleEntries))

  val scheduleProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")
  val wrappedEventJournal = new ScheduleEventJournal[F] {
    override def processNewEvents(
      f: EntityEvent[ScheduleBucketId, ScheduleEvent] => F[Unit]
    ): F[Unit] =
      schduleEventJournal
        .currentEventsByTag(EventTag("Schedule"), scheduleProcessConsumerId)
        .evalTap(_.process(f))
        .compile
        .drain
  }

  val offsetStore =
    StateKeyValueStore[F](GenLens[SpecState](_.offsetStoreState))

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
      CounterViewProcess(TestCounterViewRepository[F](GenLens[SpecState](_.counterViewState))),
      counterEventJournal
        .currentEventsByTag(CounterEvent.tag, counterViewProcessConsumerId)
    ),
    wireProcess(
      NotificationProcess(counters, notifications),
      counterEventJournal
        .currentEventsByTag(CounterEvent.tag, notificationProcessConsumerId)
        .map(_.map(Coproduct[NotificationProcess.Input](_))),
      notificationEventJournal
        .currentEventsByTag(NotificationEvent.tag, notificationProcessConsumerId)
        .map(_.map(Coproduct[NotificationProcess.Input](_)))
    ),
    scheduleProcess
  )

  import processes._

  def sleepSeconds(seconds: Long) = wire(clock.tick(java.time.Duration.ofSeconds(seconds)))

  test("Process should react to events") {

    val cs = wireK(counters)

    val firstCounterId = CounterId("1")
    val secondCounterId = CounterId("2")
    val first = cs(firstCounterId)
    val second = cs(secondCounterId)

    val program = for {
      _ <- first.increment
      _ <- first.increment
      _ <- first.decrement
      _ <- second.increment
      _ <- second.increment
    } yield ()

    val (state, _) = program
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
      .unsafeRunSync()

    println(state.counterJournalState)
    state.counterViewState.values shouldBe Map(firstCounterId -> 1L, secondCounterId -> 2L)

    state.notificationJournalState.eventsByKey
      .getOrElse("1-2", Chain.empty)
      .size shouldBe 2
  }

  test("Schedule should fire") {

    val buckets = wireK(scheduleBuckets)

    def program(n: Int): F[Unit] =
      for {
        now <- clock.localDateTime
        bucketId = ScheduleBucketId("foo", "b")
        bucket = buckets(bucketId)
        _ <- bucket.addScheduleEntry("e1", "cid", now.plusSeconds(3))
        _ <- bucket.addScheduleEntry("e2", "cid", now.plusSeconds(5))
        _ <- sleepSeconds(3)
        _ <- sleepSeconds(2)
        _ <- if (n == 0) {
              ().pure[F]
            } else {
              program(n - 1)
            }
      } yield ()

    val (state, _) = program(100)
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
      .unsafeRunSync()

    state.scheduleEntries.exists(e => e.entryId == "e1" && e.fired) shouldBe true
    state.scheduleEntries.exists(e => e.entryId == "e2" && e.fired) shouldBe true
  }
}
