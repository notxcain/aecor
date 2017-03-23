package aecor.tests

import java.util.UUID

import aecor.aggregate.Tagging
import aecor.aggregate.runtime.EventsourcedBehavior.BehaviorFailure
import aecor.aggregate.runtime._
import aecor.aggregate.runtime.behavior._
import aecor.streaming.{ Committable, ConsumerId }
import aecor.tests.e2e.CounterOp.{ Decrement, Increment }
import aecor.tests.e2e.TestCounterViewRepository.TestCounterViewRepositoryState
import aecor.tests.e2e.TestEventJournal.TestEventJournalState
import aecor.tests.e2e._
import aecor.tests.e2e.notification.{
  NotificationEvent,
  NotificationOp,
  NotificationOpHandler,
  NotificationState
}
import cats.data.StateT
import cats.implicits._
import cats.{ Functor, Monad, MonadError, ~> }
import org.scalatest.{ FunSuite, Matchers }
import shapeless.Coproduct

class EndToEndTest extends FunSuite with Matchers {

  def counterEventJournal[F[_]: Monad]: TestEventJournal[F, SpecState, CounterEvent] =
    TestEventJournal(_.counterJournalState, (x, a) => x.copy(counterJournalState = a))

  def counterBehavior[F[_]: MonadError[?[_], BehaviorFailure], A](
    journal: TestEventJournal[F, A, CounterEvent]
  ): Behavior[CounterOp, StateT[F, A, ?]] =
    EventsourcedBehavior[CounterOp, CounterState, CounterEvent, StateT[F, A, ?]](
      "Counter",
      CounterOp.correlation,
      CounterOpHandler,
      Tagging(CounterEvent.tag),
      journal,
      Option.empty,
      NoopSnapshotStore.apply,
      StateT.lift(UUID.randomUUID().pure[F])
    )

  def notificationEventJournal[F[_]: Monad]: TestEventJournal[F, SpecState, NotificationEvent] =
    TestEventJournal(_.notificationJournalState, (x, a) => x.copy(notificationJournalState = a))

  def notificationBehavior[F[_]: MonadError[?[_], BehaviorFailure], A](
    journal: TestEventJournal[F, A, NotificationEvent]
  ): Behavior[NotificationOp, StateT[F, A, ?]] =
    EventsourcedBehavior[NotificationOp, NotificationState, NotificationEvent, StateT[F, A, ?]](
      "Notification",
      NotificationOp.correlation,
      NotificationOpHandler,
      Tagging(NotificationEvent.tag),
      journal,
      Option.empty,
      NoopSnapshotStore.apply,
      StateT.lift(UUID.randomUUID().pure[F])
    )

  def ignoreNext[Op[_], F[_]: Functor, S](
    behavior: Behavior[Op, StateT[F, S, ?]]
  ): Op ~> StateT[F, S, ?] =
    new (Op ~> StateT[F, S, ?]) {
      override def apply[A](fa: Op[A]): StateT[F, S, A] =
        behavior.run(fa).map(_._2)
    }

  case class SpecState(counterJournalState: TestEventJournalState[CounterEvent],
                       notificationJournalState: TestEventJournalState[NotificationEvent],
                       counterViewState: TestCounterViewRepositoryState)

  def mkCounterBehavior[F[_]: MonadError[?[_], BehaviorFailure]]
    : Behavior[CounterOp, StateT[F, SpecState, ?]] =
    counterBehavior[F, SpecState](counterEventJournal[F])

  def mkNotificationBehavior[F[_]: MonadError[?[_], BehaviorFailure]]
    : Behavior[NotificationOp, StateT[F, SpecState, ?]] =
    notificationBehavior[F, SpecState](notificationEventJournal[F])

  val counterViewProcessConsumerId: ConsumerId = ConsumerId("CounterViewProcess")

  val notificationProcessConsumerId: ConsumerId = ConsumerId("NotificationProcess")

  def wireProcess[F[_]: Monad, S, In](
    process: In => F[Unit],
    sources: FoldableSource[F, F, Committable[F, In]]*
  ): F[Unit] =
    for {
      processed <- sources.toVector
                    .fold(FoldableSource.empty[F, F, Committable[F, In]])(_ merge _)
                    .foldM(0) {
                      case (cnt, committable) =>
                        committable
                          .traverse(process)
                          .flatMap(_ => committable.commit())
                          .map(_ => cnt + 1)
                    }
                    .flatten
      _ <- if (processed == 0)
            ().pure[F]
          else
            wireProcess[F, S, In](process, sources: _*)
    } yield ()

  def processStep[F[_]: MonadError[?[_], BehaviorFailure]]: StateT[F, SpecState, Unit] =
    wireProcess(
      CounterViewProcess(
        TestCounterViewRepository[F, SpecState](
          _.counterViewState,
          (x, a) => x.copy(counterViewState = a)
        ),
        ignoreNext(mkCounterBehavior[F])
      ),
      counterEventJournal[F]
        .foldByTag[StateT[F, SpecState, ?]](CounterEvent.tag, counterViewProcessConsumerId)
    ) >>
      wireProcess(
        NotificationProcess(
          ignoreNext(mkCounterBehavior[F]),
          ignoreNext(mkNotificationBehavior[F])
        ),
        counterEventJournal[F]
          .foldByTag[StateT[F, SpecState, ?]](CounterEvent.tag, notificationProcessConsumerId)
          .map(_.map(Coproduct[NotificationProcess.Input](_))),
        notificationEventJournal[F]
          .foldByTag[StateT[F, SpecState, ?]](NotificationEvent.tag, notificationProcessConsumerId)
          .map(_.map(Coproduct[NotificationProcess.Input](_)))
      )

  def mkWired[Op[_], F[_]: Monad](behavior: Behavior[Op, F], processStep: F[Unit]): Op ~> F =
    new (Op ~> F) {
      override def apply[A](fa: Op[A]): F[A] =
        for {
          x <- behavior.run(fa)
          _ <- processStep
        } yield x._2
    }

  test("Process should react to events") {

    val counter = mkWired(
      mkCounterBehavior[Either[BehaviorFailure, ?]],
      processStep[Either[BehaviorFailure, ?]]
    )
    val program = for {
      _ <- counter(Increment("1"))
      _ <- counter(Increment("1"))
      _ <- counter(Decrement("1"))
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

    println(state)
    state.counterViewState.value shouldBe Map("1" -> 1L, "2" -> 2L)
  }

}
