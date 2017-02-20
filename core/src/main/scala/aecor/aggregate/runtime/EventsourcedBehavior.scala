package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
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
        λ[Op ~> PairT[F, Behavior[Op, F], ?]] { firstOp =>
          for {
            entityId <- s"$entityName-${correlation(firstOp)}".pure[F]
            snapshot <- snapshotStore.loadSnapshot(entityId)
            recoveredState <- journal
                               .fold(
                                 entityId,
                                 snapshot.map(_.version).getOrElse(0L),
                                 snapshot.getOrElse(InternalState.zero),
                                 (_: InternalState[S]).step(_)
                               )
                               .flatMap {
                                 case Next(x) => x.pure[F]
                                 case Impossible =>
                                   s"Illegal fold for [$entityId]".raiseError[F, InternalState[S]]
                               }
            behavior = {
              def mkBehavior(state: InternalState[S]): Behavior[Op, F] =
                Behavior {
                  λ[Op ~> PairT[F, Behavior[Op, F], ?]] { op =>
                    val (events, reply) = opHandler(op).run(state.entityState)
                    val nextBehavior =
                      if (events.isEmpty) {
                        mkBehavior(state).pure[F]
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
                          newState <- events.foldRec[InternalState[S], F](state, _.step(_)) {
                                       case (Next(next), continue) =>
                                         snapshotStore
                                           .saveSnapshot(entityId, next)
                                           .flatMap(_ => continue(next))
                                       case _ =>
                                         s"Illegal fold for [$entityId]"
                                           .raiseError[F, InternalState[S]]

                                     }
                        } yield {
                          mkBehavior(newState)
                        }
                      }
                    nextBehavior.map((_, reply))
                  }
                }
              mkBehavior(recoveredState)
            }
            result <- behavior.run(firstOp)
          } yield result
        }
    }
}
