package aecor.tests

import java.time.Instant

import aecor.aggregate.Tagging
import aecor.aggregate.runtime.Capture
import aecor.streaming.ConsumerId
import aecor.tests.e2e.CounterOp.{ Decrement, Increment }
import aecor.tests.e2e.TestCounterViewRepository.TestCounterViewRepositoryState
import aecor.tests.e2e.TestEventJournal.TestEventJournalState
import aecor.tests.e2e._
import aecor.tests.e2e.notification.{ NotificationEvent, NotificationOp, NotificationOpHandler }
import cats.data.StateT
import cats.implicits._
import cats.~>
import org.scalatest.{ FunSuite, Matchers }
import shapeless.Coproduct

class EndToEndTest extends FunSuite with Matchers with E2eSupport {

  def instant[F[_]: Capture]: F[Instant] =
    Capture[F].capture(Instant.ofEpochMilli(System.currentTimeMillis()))

  case class SpecState(counterJournalState: TestEventJournalState[CounterEvent],
                       notificationJournalState: TestEventJournalState[NotificationEvent],
                       counterViewState: TestCounterViewRepositoryState)

  def counterEventJournal =
    mkJournal[CounterEvent](_.counterJournalState, (x, a) => x.copy(counterJournalState = a))

  def counterBehavior: ~>[CounterOp, StateT[SpecF, SpecState, ?]] =
    mkBehavior(
      "Counter",
      CounterOp.correlation,
      CounterOpHandler,
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
      NotificationOpHandler,
      Tagging(NotificationEvent.tag),
      notificationEventJournal
    )

  val counterViewProcessConsumerId: ConsumerId = ConsumerId("CounterViewProcess")

  val notificationProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")

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
        .map(_.map(Coproduct[NotificationProcess.Input](_))),
      notificationEventJournal
        .eventsByTag(NotificationEvent.tag, notificationProcessConsumerId)
        .map(_.map(Coproduct[NotificationProcess.Input](_)))
    )
  )

  test("Process should react to events") {

    val counter = wired(counterBehavior)
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
          TestCounterViewRepositoryState.init
        )
      )
      .right
      .get
    state.counterViewState.value shouldBe Map("1" -> 1L, "2" -> 2L)
    state.notificationJournalState.eventsById
      .getOrElse("Notification-1-2", Vector.empty) should have size (2)
  }
}
