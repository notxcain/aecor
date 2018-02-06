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
  final case class RunningState[S](entityState: S, version: Long) {}

  sealed abstract class BehaviorFailure extends Exception
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  final case class EventEnvelope[E](sequenceNr: Long, event: E, tags: Set[EventTag])

  def apply[F[_]: MonadError[?[_], BehaviorFailure], I, Op[_], S, E](
    entityBehavior: EventsourcedBehaviorT[F, Op, S, E],
    tagging: Tagging[I],
    journal: EventJournal[F, I, EventEnvelope[E]],
    snapshotEach: Option[Long],
    snapshotStore: KeyValueStore[F, I, RunningState[S]]
  ): I => Behavior[F, Op] = { entityId =>
    val effectiveBehavior = EventsourcedBehaviorT[F, Op, RunningState[S], EventEnvelope[E]](
      initialState = RunningState(entityBehavior.initialState, 0),
      commandHandler = entityBehavior.commandHandler
        .andThen(new (ActionT[F, S, E, ?] ~> ActionT[F, RunningState[S], EventEnvelope[E], ?]) {
          override def apply[A](
            fa: ActionT[F, S, E, A]
          ): ActionT[F, RunningState[S], EventEnvelope[E], A] =
            ActionT { rs =>
              fa.run(rs.entityState).map {
                case (es, a) =>
                  val envelopes = es.foldLeft(Seq.empty[EventEnvelope[E]]) { (acc, e) =>
                    acc :+ EventEnvelope(rs.version + acc.size + 1, e, tagging.tag(entityId))
                  }
                  (envelopes, a)
              }
            }
        }),
      applyEvent = (s, e) =>
        entityBehavior
          .applyEvent(s.entityState, e.event)
          .map(RunningState(_, s.version + 1))
    )

    def loadState: F[RunningState[S]] =
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
                      .raiseError[F, RunningState[S]]
                }
      } yield out

    def updateState(state: RunningState[S], events: Seq[EventEnvelope[E]]) =
      if (events.isEmpty) {
        state.pure[F]
      } else {
        val folded =
          events.toVector.foldM((false, state)) {
            case ((snapshotPending, s), e) =>
              effectiveBehavior.applyEvent(s, e).map { next =>
                def shouldSnapshotNow =
                  snapshotEach
                    .exists(x => next.version % x == 0)
                (snapshotPending || shouldSnapshotNow, next)
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
              .raiseError[F, RunningState[S]]
        }
      }
    Behavior.roll {
      loadState.map { s =>
        Behavior.fromState(s, new (Op ~> StateT[F, RunningState[S], ?]) {
          override def apply[A](op: Op[A]): StateT[F, RunningState[S], A] =
            StateT { state =>
              for {
                x <- effectiveBehavior.commandHandler(op).run(state)
                (events, reply) = x
                nextState <- updateState(state, events)
              } yield (nextState, reply)
            }

        })
      }
    }
  }
}
