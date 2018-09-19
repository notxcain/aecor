package aecor.runtime

import aecor.data.{ActionT, EitherK, EventsourcedBehavior, Folded}
import aecor.data.Folded.{Impossible, Next}
import aecor.data.ActionT.ActionFailure
import aecor.data.next._
import cats.data.{EitherT, NonEmptyChain}
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{MonadError, ~>}
import io.aecor.liberator.{FunctorK, Invocation, ReifiedInvocations}

object Eventsourced {
  sealed abstract class Entity[K, M[_[_]], F[_], R] {
    def apply(k: K): M[λ[x => F[Either[R, x]]]]
  }

  object Entity {
    private final class EntityImpl[K, M[_[_]], F[_], R](mfr: K => M[EitherT[F, R, ?]])(
      implicit M: FunctorK[M]
    ) extends Entity[K, M, F, R] {
      def apply(k: K): M[λ[x => F[Either[R, x]]]] =
        M.mapK[EitherT[F, R, ?], λ[x => F[Either[R, x]]]](mfr(k), new (EitherT[F, R, ?] ~> λ[x => F[Either[R, x]]]) {
          override def apply[A](fa: EitherT[F, R, A]): F[Either[R, A]] = fa.value
        })
    }

    def apply[K, M[_[_]]: FunctorK, F[_], R](
      mfr: K => M[EitherT[F, R, ?]]
    ): Entity[K, M, F, R] =
      new EntityImpl(mfr)
  }

  final case class Snapshotting[F[_], K, S](snapshotEach: Long,
                                            store: KeyValueStore[F, K, InternalState[S]])
  type EntityId = String
  final case class InternalState[S](entityState: S, version: Long)

  sealed abstract class BehaviorFailure extends Throwable
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  trait FailureHandler[F[_]] {
    def fail[A](e: BehaviorFailure): F[A]
  }

  object FailureHandler {
    implicit def monadErrorFailure[F[_]](implicit F: MonadError[F, Throwable]): FailureHandler[F] =
      new FailureHandler[F] {
        override def fail[A](e: BehaviorFailure): F[A] = F.raiseError(e)
      }
  }

  def apply[M[_[_]], F[_]: Sync, S, E, R, K](
    entityBehavior: EventsourcedBehavior[M, F, S, E, R],
    journal: EventJournal[F, K, E],
    snapshotting: Option[Snapshotting[F, K, S]] = Option.empty
  )(implicit M: ReifiedInvocations[M], F: FailureHandler[F]): K => F[EitherK[M, F, R]] = { key =>
    val effectiveActions = M.mapK(entityBehavior.actions, ActionT.xmapState[F, S, InternalState[S], E, R]((is: InternalState[S], s: S) => is.copy(entityState = s))(_.entityState))

    val initialState = InternalState(entityBehavior.initial, 0)

    val effectiveUpdate = (s: InternalState[S], e: E) =>
      entityBehavior
        .update(s.entityState, e)
        .map(InternalState(_, s.version + 1))

    val needsSnapshot: Long => Boolean = snapshotting match {
      case Some(Snapshotting(x, _)) =>
        version => version % x == 0
      case None =>
        _ => false
    }

    val snapshotStore =
      snapshotting.map(_.store).getOrElse(NoopKeyValueStore[F, K, InternalState[S]])

    def loadState: F[InternalState[S]] =
      for {
        snapshot <- snapshotStore.getValue(key)
        effectiveInitialState = snapshot.getOrElse(initialState)
        out <- journal
                .foldById(key, effectiveInitialState.version + 1, effectiveInitialState)(
                  effectiveUpdate
                )
                .flatMap {
                  case Next(x) => x.pure[F]
                  case Impossible =>
                    F.fail[InternalState[S]](
                      BehaviorFailure
                        .illegalFold(key.toString)
                    )
                }
      } yield out

    def appendEvents(currentVersion: Long, events: NonEmptyChain[E]): F[Boolean] = {
      for {
        _ <- journal.append(key, currentVersion + 1, events)
      } yield (currentVersion to (currentVersion + events.size)).exists(needsSnapshot)
    }

    def updateState(state: InternalState[S], events: NonEmptyChain[E]) = {
      val folded: Folded[(Boolean, InternalState[S])] =
        events.foldM((false, state)) {
          case ((snapshotPending, s), e) =>
            effectiveUpdate(s, e).map { next =>
              (snapshotPending || needsSnapshot(next.version), next)
            }
        }
      folded match {
        case Next((snapshotNeeded, nextState)) =>
          val appendEvents = journal
            .append(key, state.version + 1, events)
          val snapshotIfNeeded = if (snapshotNeeded) {
            snapshotStore.setValue(key, nextState)
          } else {
            ().pure[F]
          }
          (appendEvents, snapshotIfNeeded).mapN((_, _) => nextState)
        case Impossible =>
          F.fail[InternalState[S]](
            BehaviorFailure
              .illegalFold(key.toString)
          )
      }
    }

    for {
      initialState <- loadState
      ref <- Ref[F].of(initialState)
    } yield
      EitherK(M.mapInvocations(new (Invocation[M, ?] ~> EitherT[F, R, ?]) {
        override def apply[A](fa: Invocation[M, A]): EitherT[F, R, A] = EitherT {
          for {
            current <- ref.get
            action = fa.invoke(effectiveActions)
            result <- action.flatMap(a => ActionT.read[F, InternalState[S], E, R].map(s => (s, a))).run(current, effectiveUpdate)
            _ <- result match {
                  case Left(ActionFailure.ImpossibleFold) =>
                    F.fail(BehaviorFailure.illegalFold(key.toString))
                  case Left(ActionFailure.Rejection(r)) =>
                    r.asLeft[A].pure[F]
                  case Right((es, (next, a))) =>
                    NonEmptyChain
                      .fromChain(es)
                      .traverse(updateState(current, _).flatMap(ref.set))
                      .as(a.asRight[R])
                }
          } yield null
        }
      }))
  }
}
