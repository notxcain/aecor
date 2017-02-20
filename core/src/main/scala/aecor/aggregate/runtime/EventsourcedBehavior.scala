package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.runtime.RuntimeActor.InstanceIdentity
import aecor.aggregate.runtime.behavior.{ Behavior, PairT }
import aecor.aggregate.{ Correlation, Folder, SnapshotStore, Tagging }
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
  }
  object InternalState {
    def zero[F[_], S](implicit S: Folder[F, _, S]): InternalState[S] = InternalState(S.zero, 0)
  }

  implicit class FoldOps[E](as: Seq[E]) {
    def foldRec[S, F[_]: Applicative](zero: S, step: (S, E) => Folded[S])(
      rec: (Folded[S], S => F[S]) => F[S]
    ): F[S] =
      if (as.isEmpty) {
        zero.pure[F]
      } else {
        rec(step(zero, as.head), x => as.tail.foldRec(x, step)(rec))
      }
  }

  final case class InstanceIdentity(entityId: String, instanceId: UUID)

  def apply[Op[_], S, E, F[_]: MonadError[?[_], String]](
    entityName: String,
    correlation: Correlation[Op],
    opHandler: Op ~> Handler[S, E, ?],
    tagging: Tagging[E],
    journal: EventJournal[E, F],
    snapshotStore: SnapshotStore[S, F]
  )(implicit S: Folder[Folded, E, S]): UUID => Behavior[Op, F] =
    instanceId =>
      Behavior {
        new (Op ~> PairT[F, Behavior[Op, F], ?]) {
          override def apply[A](firstOp: Op[A]): PairT[F, Behavior[Op, F], A] = {
            val instanceIdentity =
              InstanceIdentity(s"$entityName-${correlation(firstOp)}", instanceId)
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
                                newState <- events.foldRec[InternalState[S], F](state, _.step(_)) {
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
                .flatMap { behavior =>
                  behavior.run(firstOp)
                }

            }
          }
        }

    }
}
