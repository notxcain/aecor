package aecor.experimental

import java.util.UUID

import aecor.data.Folded.{ Impossible, Next }
import aecor.data._
import aecor.util.KeyValueStore
import akka.cluster.sharding.ShardRegion.EntityId
import cats.data.NonEmptyVector
import cats.implicits._
import cats.{ Applicative, MonadError, ~> }

import scala.collection.immutable.{ Seq, Set }

object Eventsourced {
  final case class RunningState[S](entityState: S, version: Long)

  object RunningState {
    def folder[S, E](
      folder: Folder[Folded, E, S]
    ): Folder[Folded, EventEnvelope[E], RunningState[S]] =
      Folder(
        RunningState(folder.zero, 0),
        (s, e) => folder.reduce(s.entityState, e.event).map(RunningState(_, s.version + 1))
      )
  }

  sealed abstract class BehaviorFailure extends Exception
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  final case class EventEnvelope[E](sequenceNr: Long,
                                    instanceId: UUID,
                                    event: E,
                                    tags: Set[EventTag[E]])

  def apply[F[_]: MonadError[?[_], BehaviorFailure], Op[_], S, E](
    correlation: Correlation[Op],
    eventsourcedBehavior: EventsourcedBehavior[F, Op, S, E],
    tagging: Tagging[E],
    journal: EventJournal[F, EventEnvelope[E]],
    generateInstanceId: F[UUID],
    snapshotEach: Option[Long],
    snapshotStore: KeyValueStore[F, String, RunningState[S]]
  ): Behavior[F, Op] = Behavior.roll[F, Op] {
    generateInstanceId.map { instanceId =>
      VanillaBehavior.correlated[F, Op] { i =>
        val r = folder(
          eventsourcedBehavior.folder,
          journal,
          snapshotEach,
          snapshotStore,
          tagging,
          instanceId,
          correlation(i)
        )
        VanillaBehavior
          .shared[F, Op, RunningState[S], Seq[E]](
            mkOpHandler[F, Op, S, E](eventsourcedBehavior.handler),
            r
          )
      }
    }
  }

  def mkOpHandler[F[_], Op[_], S, E](
    opHandler: Op ~> Handler[F, S, Seq[E], ?]
  ): Op ~> Handler[F, RunningState[S], Seq[E], ?] =
    Lambda[Op ~> Handler[F, RunningState[S], Seq[E], ?]] { op =>
      Handler(s => opHandler(op).run(s.entityState))
    }

  def folder[F[_]: MonadError[?[_], BehaviorFailure], S, E](
    folder: Folder[Folded, E, S],
    journal: EventJournal[F, EventEnvelope[E]],
    snapshotEach: Option[Long],
    snapshotStore: KeyValueStore[F, String, RunningState[S]],
    tagging: Tagging[E],
    instanceId: UUID,
    entityId: String
  ): F[Folder[F, Seq[E], RunningState[S]]] =
    for {
      _ <- ().pure[F]
      internalFolder = RunningState.folder(folder)
      snapshot <- snapshotStore.getValue(entityId)
      effectiveFolder = snapshot.map(internalFolder.withZero).getOrElse(internalFolder)
      zero <- journal
               .foldById(entityId, effectiveFolder.zero.version, effectiveFolder)
               .flatMap {
                 case Next(x) => x.pure[F]
                 case Impossible =>
                   BehaviorFailure
                     .illegalFold(entityId.toString)
                     .raiseError[F, RunningState[S]]
               }
    } yield
      Folder.curried(zero) { state: RunningState[S] => events: Seq[E] =>
        if (events.isEmpty) {
          state.pure[F]
        } else {
          val folded =
            events.toVector.foldM((false, state, Vector.empty[EventEnvelope[E]])) {
              case ((snapshotPending, s, es), e) =>
                val eventEnvelope = EventEnvelope(s.version + 1, instanceId, e, tagging(e))
                internalFolder.reduce(s, eventEnvelope).map { next =>
                  def shouldSnapshotNow =
                    snapshotEach
                      .exists(x => next.version % x == 0)
                  (snapshotPending || shouldSnapshotNow, next, es :+ eventEnvelope)
                }
            }
          folded match {
            case Next((snapshotNeeded, nextState, envelopes)) =>
              for {
                _ <- journal
                      .append(entityId, NonEmptyVector.of(envelopes.head, envelopes.tail: _*))
                _ <- if (snapshotNeeded) {
                      snapshotStore.setValue(entityId, nextState).map(_ => nextState)
                    } else {
                      nextState.pure[F]
                    }
              } yield nextState
            case Impossible =>
              BehaviorFailure
                .illegalFold(entityId.toString)
                .raiseError[F, RunningState[S]]
          }
        }

      }
}
