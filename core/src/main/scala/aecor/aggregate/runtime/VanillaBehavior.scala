package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.runtime.EventsourcedBehavior.{ BehaviorFailure, InternalState }
import aecor.aggregate.runtime.behavior.{ Behavior, PairT }
import aecor.aggregate.{ Correlation, Folder, SnapshotStore, Tagging }
import aecor.data.Folded
import aecor.data.Folded.{ Impossible, Next }
import akka.cluster.sharding.ShardRegion.EntityId
import cats.arrow.FunctionK
import cats.data.NonEmptyVector
import cats.implicits._
import cats.{ Monad, MonadError, ~> }

import scala.collection.immutable._

object VanillaBehavior {
  final case class Handler[S, D, A](run: S => (D, A)) extends AnyVal
  def apply[F[_]: Monad, Op[_], S, D](entityName: String,
                                      correlation: Correlation[Op],
                                      opHandler: Op ~> Handler[S, D, ?],
                                      loadState: String => F[S],
                                      updateState: (String, UUID, S, D) => F[S],
                                      generateInstanceId: F[UUID]): Behavior[Op, F] = {
    def mkBehavior(entityId: EntityId, instanceId: UUID, stateZero: S): Behavior[Op, F] = {
      def rec(state: S): Behavior[Op, F] =
        Behavior[Op, F] {
          def mk[A](op: Op[A]): PairT[F, Behavior[Op, F], A] = {
            val (stateChanges, reply) = opHandler(op).run(state)
            updateState(entityId, instanceId, state, stateChanges).map { nextState =>
              (rec(nextState), reply)
            }
          }
          FunctionK.lift(mk _)
        }
      rec(stateZero)
    }
    Behavior[Op, F](new (Op ~> PairT[F, Behavior[Op, F], ?]) {
      override def apply[A](firstOp: Op[A]): PairT[F, Behavior[Op, F], A] =
        for {
          instanceId <- generateInstanceId
          entityId = s"$entityName-${correlation(firstOp)}"
          state <- loadState(entityId)
          behavior = mkBehavior(entityId, instanceId, state)
          result <- behavior.run(firstOp)
        } yield result
    })
  }

  def eventsourcedLoadState[F[_]: MonadError[?[_], BehaviorFailure], S, E](
    journal: EventJournal[F, E],
    snapshotStore: SnapshotStore[S, F]
  )(implicit S: Folder[Folded, E, S]): String => F[InternalState[S]] = { entityId =>
    for {
      snapshot <- snapshotStore.loadSnapshot(entityId)
      state <- journal
                .fold(
                  entityId,
                  snapshot.map(_.version).getOrElse(0L),
                  snapshot.getOrElse(InternalState.zero),
                  (_: InternalState[S]).step(_)
                )
                .flatMap {
                  case Next(x) => x.pure[F]
                  case Impossible =>
                    BehaviorFailure
                      .illegalFold(entityId)
                      .raiseError[F, InternalState[S]]
                }
    } yield state
  }

  def eventsourcedUpdateState[F[_]: MonadError[?[_], BehaviorFailure], S, E](
    journal: EventJournal[F, E],
    snapshotStore: SnapshotStore[S, F],
    tagging: Tagging[E]
  )(
    implicit S: Folder[Folded, E, S]
  ): (String, UUID, InternalState[S], Seq[E]) => F[InternalState[S]] = {
    (entityId, instanceId, state, events) =>
      import EventsourcedBehavior._
      if (events.isEmpty) {
        state.pure[F]
      } else {
        val envelopes = events.zipWithIndex.map {
          case (e, idx) => EventEnvelope(state.version + idx, e, tagging(e))
        }
        for {
          _ <- journal
                .append(
                  entityId,
                  instanceId,
                  NonEmptyVector.of(envelopes.head, envelopes.tail: _*)
                )
          newState <- events.foldRec[InternalState[S], F](state, _ step _) {
                       case (Next(next), continue) =>
                         snapshotStore
                           .saveSnapshot(entityId, next)
                           .flatMap(_ => continue(next))
                       case _ =>
                         BehaviorFailure
                           .illegalFold(entityId)
                           .raiseError[F, InternalState[S]]

                     }
        } yield newState
      }
  }

}
