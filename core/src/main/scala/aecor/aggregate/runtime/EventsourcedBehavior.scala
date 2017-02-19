package aecor.aggregate.runtime

import EventJournal.EventEnvelope
import aecor.aggregate.runtime.RuntimeActor.InstanceIdentity
import aecor.aggregate.runtime.behavior.{ Behavior, PairT }
import aecor.aggregate.{ Folder, SnapshotStore, Tagging }
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Folded, Handler }
import cats.arrow.FunctionK
import cats.data.NonEmptyVector
import cats.implicits._
import cats.{ Applicative, Functor, MonadError, ~> }

import scala.collection.immutable.Seq

object EventsourcedBehavior {
  final case class InternalState[S](entityState: S, version: Long) {
    def step[F[_]: Functor, E](e: E)(implicit S: Folder[F, E, S]): F[InternalState[S]] =
      S.step(entityState, e).map(next => copy(entityState = next, version + 1))

    def stepWhilePossible[F[_]: Applicative, E](as: Seq[E])(
      rec: (Folded[InternalState[S]], InternalState[S] => F[InternalState[S]]) => F[
        InternalState[S]
      ]
    )(implicit S: Folder[Folded, E, S]): F[InternalState[S]] =
      if (as.isEmpty) {
        this.pure[F]
      } else {
        rec(step[Folded, E](as.head), _.stepWhilePossible(as.tail)(rec))
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
            _.step[Folded, E](_)
          )
          .flatMap {
            case Next(recoveredState) =>
              def withState(state: InternalState[S]): Behavior[Op, F] = {
                def mk[A](op: Op[A]): PairT[F, Behavior[Op, F], A] = {
                  println(s"currentState = $state")
                  val (events, reply) = opHandler(op).run(state.entityState)
                  val envelopes = events.zipWithIndex.map {
                    case (e, idx) => EventEnvelope(state.version + idx, e, tagging(e))
                  }
                  println(envelopes)
                  if (envelopes.isEmpty) {
                    (withState(state), reply).pure[F]
                  } else {
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
                      println(s"newState = $newState")
                      (withState(newState), reply)
                    }
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
