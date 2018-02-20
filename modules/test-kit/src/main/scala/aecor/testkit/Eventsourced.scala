package aecor.testkit

import aecor.ReifiedInvocation
import aecor.data.Folded.{ Impossible, Next }
import aecor.data._
import aecor.util.KeyValueStore
import cats.data.{ NonEmptyVector, StateT }
import cats.implicits._
import cats.{ MonadError, ~> }
import io.aecor.liberator.syntax._
import io.aecor.liberator.FunctorK

import scala.collection.immutable.Set

object Eventsourced {
  type EntityId = String
  final case class InternalState[S](entityState: S, version: Long) {}

  sealed abstract class BehaviorFailure extends Exception
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  final case class EventEnvelope[E](sequenceNr: Long, event: E, tags: Set[EventTag])

  def apply[M[_[_]]: FunctorK, F[_]: MonadError[?[_], BehaviorFailure], S, E, I](
    entityBehavior: EventsourcedBehaviorT[M, F, S, E],
    tagging: Tagging[I],
    journal: EventJournal[F, I, EventEnvelope[E]],
    snapshotEach: Option[Long],
    snapshotStore: KeyValueStore[F, I, InternalState[S]]
  )(implicit M: ReifiedInvocation[M]): I => Behavior[M, F] = { entityId =>
    val internalize =
      new (ActionT[F, S, E, ?] ~> ActionT[F, InternalState[S], EventEnvelope[E], ?]) {
        override def apply[A](
          fa: ActionT[F, S, E, A]
        ): ActionT[F, InternalState[S], EventEnvelope[E], A] =
          ActionT { rs =>
            fa.run(rs.entityState).map {
              case (es, a) =>
                val envelopes = es.foldLeft(Vector.empty[EventEnvelope[E]]) { (acc, e) =>
                  acc :+ EventEnvelope(rs.version + acc.size + 1, e, tagging.tag(entityId))
                }
                (envelopes.toList, a)
            }
          }
      }

    val effectiveBehavior = EventsourcedBehaviorT[M, F, InternalState[S], EventEnvelope[E]](
      initialState = InternalState(entityBehavior.initialState, 0),
      actions = entityBehavior.actions
        .mapK(internalize),
      applyEvent = (s, e) =>
        entityBehavior
          .applyEvent(s.entityState, e.event)
          .map(InternalState(_, s.version + 1))
    )

    def loadState: F[InternalState[S]] =
      for {
        snapshot <- snapshotStore.getValue(entityId)
        effectiveInitialState = snapshot.getOrElse(effectiveBehavior.initialState)
        out <- journal
                .foldById(entityId, effectiveInitialState.version, effectiveInitialState)(
                  effectiveBehavior.applyEvent
                )
                .flatMap {
                  case Next(x) => x.pure[F]
                  case Impossible =>
                    BehaviorFailure
                      .illegalFold(entityId.toString)
                      .raiseError[F, InternalState[S]]
                }
      } yield out

    def needsSnapshot(state: InternalState[S]): Boolean =
      snapshotEach
        .exists(x => state.version % x == 0)

    def updateState(state: InternalState[S], events: List[EventEnvelope[E]]) =
      if (events.isEmpty) {
        state.pure[F]
      } else {
        val folded: Folded[(Boolean, InternalState[S])] =
          events.foldM((false, state)) {
            case ((snapshotPending, s), e) =>
              effectiveBehavior.applyEvent(s, e).map { next =>
                (snapshotPending || needsSnapshot(next), next)
              }
          }
        folded match {
          case Next((snapshotNeeded, nextState)) =>
            val appendEvents = journal
              .append(entityId, NonEmptyVector.of(events.head, events.tail: _*))
            val snapshotIfNeeded = if (snapshotNeeded) {
              snapshotStore.setValue(entityId, nextState)
            } else {
              ().pure[F]
            }
            (appendEvents, snapshotIfNeeded).mapN((_, _) => nextState)
          case Impossible =>
            BehaviorFailure
              .illegalFold(entityId.toString)
              .raiseError[F, InternalState[S]]
        }
      }
    Behavior.roll {
      loadState.map { initialState =>
        val x = effectiveBehavior.actions.mapK {
          new (ActionT[F, InternalState[S], EventEnvelope[E], ?] ~> StateT[F, InternalState[S], ?]) {
            override def apply[A](
              action: ActionT[F, InternalState[S], EventEnvelope[E], A]
            ): StateT[F, InternalState[S], A] =
              StateT { state =>
                for {
                  x <- action.run(state)
                  (events, reply) = x
                  nextState <- updateState(state, events)
                } yield (nextState, reply)
              }

          }
        }
        Behavior.fromState(initialState, x)
      }
    }
  }
}
