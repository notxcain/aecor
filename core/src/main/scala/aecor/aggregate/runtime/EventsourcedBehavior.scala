package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate._
import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.runtime.VanillaBehavior.EntityRepository
import aecor.aggregate.runtime.behavior.Behavior
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Folded, Handler }
import akka.cluster.sharding.ShardRegion.EntityId
import cats.data.NonEmptyVector
import cats.implicits._
import cats.{ MonadError, ~> }

import scala.collection.immutable.Seq

object EventsourcedBehavior {
  final case class InternalState[S](entityState: S, version: Long) {
    def step[E](e: E)(implicit S: Folder[Folded, E, S]): Folded[InternalState[S]] =
      S.step(entityState, e).map(InternalState(_, version + 1))
  }
  object InternalState {
    def zero[F[_], S](implicit S: Folder[F, _, S]): InternalState[S] = InternalState(S.zero, 0)
  }

  sealed abstract class BehaviorFailure extends Exception
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  def apply[Op[_], S, E, F[_]: MonadError[?[_], BehaviorFailure]](
    entityName: String,
    correlation: Correlation[Op],
    opHandler: Op ~> Handler[S, Seq[E], ?],
    tagging: Tagging[E],
    journal: EventJournal[F, E],
    snapshotEach: Option[Long],
    snapshotStore: SnapshotStore[S, F],
    generateInstanceId: F[UUID]
  )(implicit S: Folder[Folded, E, S]): Behavior[Op, F] =
    VanillaBehavior.correlated[F, Op, InternalState[S], Seq[E]](
      entityName,
      correlation,
      repository(journal, snapshotEach, snapshotStore, tagging).andThen { r =>
        VanillaBehavior
          .shared[F, Op, InternalState[S], Seq[E]](mkOpHandler(opHandler), r, generateInstanceId)
      }
    )

  def mkOpHandler[Op[_], S, E](
    opHandler: Op ~> Handler[S, Seq[E], ?]
  ): Op ~> Handler[InternalState[S], Seq[E], ?] =
    Lambda[Op ~> Handler[InternalState[S], Seq[E], ?]] { op =>
      Handler(s => opHandler(op).run(s.entityState))
    }

  def repository[F[_]: MonadError[?[_], BehaviorFailure], S, E](journal: EventJournal[F, E],
                                                                snapshotEach: Option[Long],
                                                                snapshotStore: SnapshotStore[S, F],
                                                                tagging: Tagging[E])(
    implicit S: Folder[Folded, E, S]
  ): CorrelationId => EntityRepository[F, InternalState[S], Seq[E]] = { entityId =>
    new EntityRepository[F, InternalState[S], Seq[E]] {
      override def loadState: F[InternalState[S]] =
        for {
          snapshot <- snapshotStore.loadSnapshot(entityId)
          state <- journal
                    .foldById(
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

      override def applyChanges(instanceId: UUID,
                                state: InternalState[S],
                                events: Seq[E]): F[InternalState[S]] =
        if (events.isEmpty) {
          state.pure[F]
        } else {
          val folded =
            events.toVector.foldM((false, state, Vector.empty[EventEnvelope[E]])) {
              case ((snap, s, es), e) =>
                s.step(e).map { next =>
                  val snapshotNeeded = snap || snapshotEach
                    .map(next.version % _)
                    .contains(0)
                  (snapshotNeeded, next, es :+ EventEnvelope(next.version, e, tagging(e)))
                }
            }
          folded match {
            case Next((snapshotNeeded, nextState, envelopes)) =>
              for {
                _ <- journal
                      .append(
                        entityId,
                        instanceId,
                        NonEmptyVector.of(envelopes.head, envelopes.tail: _*)
                      )
                _ <- if (snapshotNeeded) {
                      snapshotStore.saveSnapshot(entityId, nextState).map(_ => nextState)
                    } else {
                      nextState.pure[F]
                    }
              } yield nextState
            case Impossible =>
              BehaviorFailure
                .illegalFold(entityId)
                .raiseError[F, InternalState[S]]
          }
        }
    }
  }
}
