package aecor.runtime

import aecor.data.Folded.{ Impossible, Next }
import aecor.data._
import cats.data.{ NonEmptyVector, StateT }
import cats.implicits._
import cats.~>
import cats.{ Monad, MonadError }
import cats.effect.Sync
import io.aecor.liberator.syntax._
import io.aecor.liberator.{ FunctorK, ReifiedInvocations }

object Eventsourced {
  type EntityId = String
  final case class InternalState[S](entityState: S, version: Long)

  sealed abstract class BehaviorFailure extends Exception
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  trait FailureHandler[F[_]] {
    def fail[A](e: BehaviorFailure): F[A]
  }

  object FailureHandler {
    implicit def syncFailure[F[_]](implicit F: Sync[F]): FailureHandler[F] = new FailureHandler[F] {
      override def fail[A](e: BehaviorFailure): F[A] = F.raiseError(e)
    }
    implicit def monadErrorFailure[F[_]](
      implicit F: MonadError[F, BehaviorFailure]
    ): FailureHandler[F] = new FailureHandler[F] {
      override def fail[A](e: BehaviorFailure): F[A] = F.raiseError(e)
    }
  }

  def apply[M[_[_]]: FunctorK, F[_]: Monad, S, E, K](
    entityBehavior: EventsourcedBehaviorT[M, F, S, E],
    journal: EventJournal[F, K, E],
    snapshotting: Option[(Long, KeyValueStore[F, K, InternalState[S]])] = Option.empty
  )(implicit M: ReifiedInvocations[M], F: FailureHandler[F]): K => Behavior[M, F] = { entityId =>
    val internalize =
      new (ActionT[F, S, E, ?] ~> ActionT[F, InternalState[S], E, ?]) {
        override def apply[A](fa: ActionT[F, S, E, A]): ActionT[F, InternalState[S], E, A] =
          fa.mapState(_.entityState)
      }

    val effectiveBehavior = EventsourcedBehaviorT[M, F, InternalState[S], E](
      initialState = InternalState(entityBehavior.initialState, 0),
      actions = entityBehavior.actions
        .mapK(internalize),
      applyEvent = (s, e) =>
        entityBehavior
          .applyEvent(s.entityState, e)
          .map(InternalState(_, s.version + 1))
    )

    val needsSnapshot = snapshotting match {
      case Some((x, _)) =>
        (state: InternalState[S]) =>
          state.version % x == 0
      case None =>
        (_: InternalState[S]) =>
          false
    }

    val snapshotStore =
      snapshotting.map(_._2).getOrElse(NoopKeyValueStore[F, K, InternalState[S]])

    def loadState: F[InternalState[S]] =
      for {
        snapshot <- snapshotStore.getValue(entityId)
        effectiveInitialState = snapshot.getOrElse(effectiveBehavior.initialState)
        out <- journal
                .foldById(entityId, effectiveInitialState.version + 1, effectiveInitialState)(
                  effectiveBehavior.applyEvent
                )
                .flatMap {
                  case Next(x) => x.pure[F]
                  case Impossible =>
                    F.fail[InternalState[S]](
                      BehaviorFailure
                        .illegalFold(entityId.toString)
                    )
                }
      } yield out

    def updateState(state: InternalState[S], events: List[E]) =
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
              .append(entityId, state.version + 1, NonEmptyVector.of(events.head, events.tail: _*))
            val snapshotIfNeeded = if (snapshotNeeded) {
              snapshotStore.setValue(entityId, nextState)
            } else {
              ().pure[F]
            }
            (appendEvents, snapshotIfNeeded).mapN((_, _) => nextState)
          case Impossible =>
            F.fail[InternalState[S]](
              BehaviorFailure
                .illegalFold(entityId.toString)
            )
        }
      }
    Behavior.roll {
      loadState.map { initialState =>
        val x = effectiveBehavior.actions.mapK {
          new (ActionT[F, InternalState[S], E, ?] ~> StateT[F, InternalState[S], ?]) {
            override def apply[A](
              action: ActionT[F, InternalState[S], E, A]
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
