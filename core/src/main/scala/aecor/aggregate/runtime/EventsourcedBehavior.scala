package aecor.aggregate.runtime

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.runtime.RuntimeActor.InstanceIdentity
import aecor.aggregate.runtime.behavior.{ Behavior, PairT }
import aecor.aggregate.{ Folder, SnapshotStore, Tagging }
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Folded, Handler }
import cats.data.NonEmptyVector
import cats.implicits._
import cats.{ Applicative, MonadError, ~> }

import scala.collection.immutable.Seq

object EventsourcedBehavior {
  final case class InternalState[S](entityState: S, version: Long) {
    def step[E](e: E)(implicit S: Folder[Folded, E, S]): Folded[InternalState[S]] =
      S.step(entityState, e).map(InternalState(_, version + 1))

    def stepWhilePossible[F[_]: Applicative, E](as: Seq[E])(
      rec: (Folded[InternalState[S]], InternalState[S] => F[InternalState[S]]) => F[
        InternalState[S]
      ]
    )(implicit S: Folder[Folded, E, S]): F[InternalState[S]] =
      if (as.isEmpty) {
        this.pure[F]
      } else {
        rec(step(as.head), _.stepWhilePossible(as.tail)(rec))
      }
  }
  object InternalState {
    def zero[F[_], S](implicit S: Folder[F, _, S]): InternalState[S] = InternalState(S.zero, 0)
  }

  def apply[Op[_], S, E, F[_]: MonadError[?[_], String]](
    journal: EventJournal[E, F],
    snapshotStore: SnapshotStore[S, F],
    opHandler: Op ~> Handler[S, E, ?],
    tagging: Tagging[E]
  )(implicit S: Folder[Folded, E, S]): InstanceIdentity => F[Behavior[Op, F]] = {
    instanceIdentity =>
      snapshotStore.loadSnapshot(instanceIdentity.entityId).flatMap { snapshot =>
        journal
          .fold(
            instanceIdentity.entityId,
            snapshot.map(_.version).getOrElse(0L),
            snapshot.getOrElse(InternalState.zero),
            (_: InternalState[S]).step(_)
          )
          .flatMap {
            case Next(recoveredState) =>
              def withState(state: InternalState[S]): Behavior[Op, F] =
                Behavior {
                  Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { op =>
                    val (events, reply) = opHandler(op).run(state.entityState)
                    val nextBehavior =
                      if (events.isEmpty) {
                        withState(state).pure[F]
                      } else {
                        val envelopes = events.zipWithIndex.map {
                          case (e, idx) => EventEnvelope(state.version + idx, e, tagging(e))
                        }
                        for {
                          _ <- journal
                                .append(
                                  instanceIdentity.entityId,
                                  instanceIdentity.instanceId,
                                  NonEmptyVector.of(envelopes.head, envelopes.tail: _*)
                                )
                          newState <- state.stepWhilePossible[F, E](events) {
                                       case (Next(next), continue) =>
                                         snapshotStore
                                           .saveSnapshot(instanceIdentity.entityId, next)
                                           .flatMap(_ => continue(next))
                                       case _ =>
                                         s"Illegal fold for [$instanceIdentity]"
                                           .raiseError[F, InternalState[S]]

                                     }
                        } yield {
                          withState(newState)
                        }
                      }
                    nextBehavior.map((_, reply))
                  }
                }
              withState(recoveredState).pure[F]
            case Impossible =>
              s"Illegal fold for [$instanceIdentity]".raiseError[F, Behavior[Op, F]]
          }
      }
  }
}
