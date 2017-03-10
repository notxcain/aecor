package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.runtime.behavior.{ Behavior, PairT }
import aecor.aggregate.{ Correlation, Folder, SnapshotStore, Tagging }
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Folded, Handler }
import akka.cluster.sharding.ShardRegion.EntityId
import cats.arrow.FunctionK
import cats.data.{ EitherT, NonEmptyVector }
import cats.implicits._
import cats.{ Applicative, Functor, MonadError, ~> }

import scala.collection.immutable.Seq

object EventsourcedBehavior {
  final case class InternalState[S](entityState: S, version: Long) {
    def step[E](e: E)(implicit S: Folder[Folded, E, S]): Folded[InternalState[S]] =
      S.step(entityState, e).map(InternalState(_, version + 1))
  }
  object InternalState {
    def zero[F[_], S](implicit S: Folder[F, _, S]): InternalState[S] = InternalState(S.zero, 0)
  }

  implicit class FoldOps[E](val self: Seq[E]) extends AnyVal {
    def foldRec[S, F[_]: Applicative](zero: S, step: (S, E) => Folded[S])(
      rec: (Folded[S], S => F[S]) => F[S]
    ): F[S] =
      if (self.isEmpty) {
        zero.pure[F]
      } else {
        rec(step(zero, self.head), x => self.tail.foldRec(x, step)(rec))
      }
  }

  def apply[Op[_], S, E, F[_]: MonadError[?[_], String]](
    entityName: String,
    correlation: Correlation[Op],
    opHandler: Op ~> Handler[S, E, ?],
    tagging: Tagging[E],
    journal: EventJournal[E, F],
    snapshotStore: SnapshotStore[S, F],
    generateInstanceId: () => F[UUID]
  )(implicit S: Folder[Folded, E, S]): Behavior[Op, F] = {
    def mkBehavior(entityId: EntityId,
                   instanceId: UUID,
                   stateZero: InternalState[S]): Behavior[Op, F] = {
      def rec(state: InternalState[S]): Behavior[Op, F] =
        Behavior {
          def mk[A](op: Op[A]): PairT[F, Behavior[Op, F], A] = {
            val (events, reply) = opHandler(op).run(state.entityState)
            if (events.isEmpty) {
              (rec(state), reply).pure[F]
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
                newState <- events.foldRec[InternalState[S], F](state, _ step _) {
                             case (Next(next), continue) =>
                               snapshotStore
                                 .saveSnapshot(entityId, next)
                                 .flatMap(_ => continue(next))
                             case _ =>
                               s"Illegal fold for [$entityId]"
                                 .raiseError[F, InternalState[S]]

                           }
              } yield {
                (rec(newState), reply)
              }
            }
          }
          FunctionK.lift(mk _)
        }
      rec(stateZero)
    }
    Behavior {
      def mk[A](firstOp: Op[A]): PairT[F, Behavior[Op, F], A] =
        for {
          instanceId <- generateInstanceId()
          entityId = s"$entityName-${correlation(firstOp)}"
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
          behavior = mkBehavior(entityId, instanceId, recoveredState)
          result <- behavior.run(firstOp)
        } yield result
      FunctionK.lift[Op, PairT[F, Behavior[Op, F], ?]](mk _)
    }
  }
}
