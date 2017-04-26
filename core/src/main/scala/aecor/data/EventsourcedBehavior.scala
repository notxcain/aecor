package aecor.data

import java.util.UUID

import aecor.aggregate._
import aecor.data.EventJournal.EventEnvelope
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.VanillaBehavior.EntityRepository
import akka.cluster.sharding.ShardRegion.EntityId
import cats.data.NonEmptyVector
import cats.implicits._
import cats.{ MonadError, ~> }

import scala.collection.immutable.Seq

object EventsourcedBehavior {
  final case class InternalState[S](entityState: S, version: Long) {
    def step[E](e: E)(reduce: (S, E) => Folded[S]): Folded[InternalState[S]] =
      reduce(entityState, e).map(InternalState(_, version + 1))
  }
  object InternalState {
    def zero[S](zeroEntityState: S): InternalState[S] = InternalState(zeroEntityState, 0)
  }

  sealed abstract class BehaviorFailure extends Exception
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  def apply[F[_]: MonadError[?[_], BehaviorFailure], Op[_], S, E](
    entityName: String,
    correlation: Correlation[Op],
    opHandler: Op ~> Handler[F, S, Seq[E], ?],
    tagging: Tagging[E],
    journal: EventJournal[F, E],
    generateInstanceId: F[UUID],
    snapshotEach: Option[Long],
    snapshotStore: KeyValueStore[F, String, InternalState[S]]
  )(implicit S: Folder[Folded, E, S]): Behavior[F, Op] = Behavior.roll {
    generateInstanceId.map { instanceId =>
      VanillaBehavior.correlated[F, Op, InternalState[S], Seq[E]] { op =>
        val entityId = s"$entityName-${correlation(op)}"
        val r = repository(S, journal, snapshotEach, snapshotStore, tagging, instanceId, entityId)
        VanillaBehavior
          .shared[F, Op, InternalState[S], Seq[E]](mkOpHandler(opHandler), r)
      }
    }
  }

  def mkOpHandler[F[_], Op[_], S, E](
    opHandler: Op ~> Handler[F, S, Seq[E], ?]
  ): Op ~> Handler[F, InternalState[S], Seq[E], ?] =
    Lambda[Op ~> Handler[F, InternalState[S], Seq[E], ?]] { op =>
      Handler(s => opHandler(op).run(s.entityState))
    }

  def repository[F[_]: MonadError[?[_], BehaviorFailure], S, E](
    folder: Folder[Folded, E, S],
    journal: EventJournal[F, E],
    snapshotEach: Option[Long],
    snapshotStore: KeyValueStore[F, String, InternalState[S]],
    tagging: Tagging[E],
    instanceId: UUID,
    entityId: CorrelationId
  ): EntityRepository[F, InternalState[S], Seq[E]] =
    new EntityRepository[F, InternalState[S], Seq[E]] {
      override def loadState: F[InternalState[S]] =
        for {
          snapshot <- snapshotStore.getValue(entityId)
          offset = snapshot.map(_.version).getOrElse(0L)
          zeroState = snapshot.getOrElse(InternalState.zero(folder.zero))
          state <- journal
                    .foldById(
                      entityId,
                      offset,
                      zeroState,
                      (_: InternalState[S]).step(_)(folder.reduce)
                    )
                    .flatMap {
                      case Next(x) => x.pure[F]
                      case Impossible =>
                        BehaviorFailure
                          .illegalFold(entityId)
                          .raiseError[F, InternalState[S]]
                    }
        } yield state

      override def applyChanges(state: InternalState[S], events: Seq[E]): F[InternalState[S]] =
        if (events.isEmpty) {
          state.pure[F]
        } else {
          val folded =
            events.toVector.foldM((false, state, Vector.empty[EventEnvelope[E]])) {
              case ((snap, s, es), e) =>
                s.step(e)(folder.reduce).map { next =>
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
                      snapshotStore.setValue(entityId, nextState).map(_ => nextState)
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
