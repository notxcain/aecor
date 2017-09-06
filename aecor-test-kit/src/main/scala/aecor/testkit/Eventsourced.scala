package aecor.testkit

import aecor.data.Folded.{ Impossible, Next }
import aecor.data._
import aecor.util.KeyValueStore
import akka.cluster.sharding.ShardRegion.EntityId
import cats.data.{ NonEmptyVector, StateT }
import cats.implicits._
import cats.{ MonadError, ~> }

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

  final case class EventEnvelope[E](sequenceNr: Long, event: E, tags: Set[EventTag])

  def apply[F[_]: MonadError[?[_], BehaviorFailure], Op[_], S, E](
    correlation: Correlation[Op],
    entityBehavior: EventsourcedBehavior[F, Op, S, E],
    tagging: Tagging[E],
    journal: EventJournal[F, String, EventEnvelope[E]],
    snapshotEach: Option[Long],
    snapshotStore: KeyValueStore[F, String, RunningState[S]]
  ): Behavior[F, Op] =
    Behavior.correlated[F, Op] { i =>
      val entityId = correlation(i)
      val internalFolder = RunningState.folder(entityBehavior.folder)
      def loadState: F[RunningState[S]] =
        for {
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
        } yield zero

      def updateState(state: RunningState[S], events: Seq[E]) =
        if (events.isEmpty) {
          state.pure[F]
        } else {
          val folded =
            events.toVector.foldM((false, state, Vector.empty[EventEnvelope[E]])) {
              case ((snapshotPending, s, es), e) =>
                val eventEnvelope = EventEnvelope(s.version + 1, e, tagging(e))
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
      Behavior.roll {
        loadState.map { s =>
          Behavior.fromState(s, new (Op ~> StateT[F, RunningState[S], ?]) {
            override def apply[A](op: Op[A]): StateT[F, RunningState[S], A] =
              StateT { state =>
                for {
                  x <- entityBehavior.handlerSelector(op).run(state.entityState)
                  (events, reply) = x
                  nextState <- updateState(state, events)
                } yield (nextState, reply)
              }

          })
        }
      }
    }
}
