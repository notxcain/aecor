package aecor.aggregate.runtime

import EventJournal.EventEnvelope
import aecor.aggregate.runtime.RuntimeActor.InstanceIdentity
import aecor.aggregate.runtime.behavior.{ Behavior, Tuple2T }
import aecor.aggregate.{ Folder, SnapshotStore, Tagging }
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Folded, Handler }
import cats.arrow.FunctionK
import cats.data.NonEmptyVector
import cats.implicits._
import cats.{ Functor, MonadError, ~> }

import scala.collection.immutable.Seq

object EventsourcedBehavior {
  final case class InternalState[S](entityState: S, version: Long)
  object InternalState {
    implicit def folder[F[_]: Functor, S, E](
      implicit S: Folder[F, E, S]
    ): Folder[F, E, InternalState[S]] =
      Folder.instance[F, E, InternalState[S]](InternalState(S.zero, 0)) { next => e =>
        S.step(next.entityState, e).map { n =>
          next.copy(entityState = n, next.version + 1)
        }
      }
  }
  def apply[J, Snap, Op[_], S, E, F[_]: MonadError[?[_], String]](
    journal: EventJournal[E, F],
    snapshotStore: SnapshotStore[S, F],
    opHandler: Op ~> Handler[S, E, ?],
    tagging: Tagging[E]
  )(implicit S: Folder[Folded, E, S]): InstanceIdentity => F[Behavior[Op, F]] = {
    instanceIdentity =>
      snapshotStore.loadSnapshot(instanceIdentity.entityId).flatMap { snapshot =>
        journal
          .fold(instanceIdentity.entityId, snapshot.map(_.version).getOrElse(0L), snapshot)
          .flatMap {
            case Next(recoveredState) =>
              def withState(state: InternalState[S]): Behavior[Op, F] = {
                def mk[A](op: Op[A]): Tuple2T[F, Behavior[Op, F], A] = {
                  val (events, reply) = opHandler(op).run(state.entityState)
                  val envelopes = events.zipWithIndex.map {
                    case (e, idx) => EventEnvelope(state.version + idx, e, tagging(e))
                  }
                  if (envelopes.isEmpty) {
                    (withState(state), reply).pure[F]
                  } else {
                    journal
                      .append(
                        instanceIdentity.entityId,
                        instanceIdentity.instanceId,
                        NonEmptyVector.of(envelopes.head, envelopes.tail: _*)
                      )
                      .flatMap { _ =>
                        def foldUntilImpossible(as: Seq[E],
                                                zero: InternalState[S]): F[InternalState[S]] =
                          if (as.isEmpty) {
                            zero.pure[F]
                          } else {
                            Folder[Folded, E, InternalState[S]].step(zero, as.head) match {
                              case Next(s) =>
                                snapshotStore
                                  .saveSnapshot(instanceIdentity.entityId, s)
                                  .flatMap(_ => foldUntilImpossible(as.tail, s))
                              case Impossible =>
                                s"Illegal fold for [${instanceIdentity.entityId}]"
                                  .raiseError[F, InternalState[S]]
                            }
                          }

                        foldUntilImpossible(events, state)
                      }
                      .map(newState => (withState(newState), reply))
                  }
                }
                Behavior(FunctionK.lift(mk _))
              }
              withState(recoveredState).pure[F]
            case Impossible =>
              s"Illegal fold for [$instanceIdentity]".raiseError[F, Behavior[Op, F]]
          }
      }
  }
}
