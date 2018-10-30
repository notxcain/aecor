package aecor.runtime

import aecor.data.Folded.{ Impossible, Next }
import aecor.data._
import cats.arrow.FunctionK
import cats.data.{ EitherT, NonEmptyChain }
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import cats.{ MonadError, ~> }

object Eventsourced {

  type ActionRunner[F[_], S, E] = FunctionK[ActionT[F, S, E, ?], F]

  final class DefaultActionRunner[F[_], K, S, E](
    key: K,
    initial: S,
    update: (S, E) => Folded[S],
    journal: EventJournal[F, K, E],
    snapshotting: Option[Snapshotting[F, K, S]],
    ref: Ref[F, Option[InternalState[S]]]
  )(implicit F: MonadError[F, Throwable])
      extends ActionRunner[F, S, E] {

    private val effectiveUpdate = (s: InternalState[S], e: E) =>
      update(s.entityState, e)
        .map(InternalState(_, s.version + 1))

    private val needsSnapshot: Long => Boolean = snapshotting match {
      case Some(Snapshotting(x, _)) =>
        version =>
          version % x == 0
      case None =>
        Function.const(false)
    }

    private val snapshotStore =
      snapshotting.map(_.store).getOrElse(NoopKeyValueStore[F, K, InternalState[S]])

    override def apply[A](action: ActionT[F, S, E, A]): F[A] =
      getInternal.flatMap(runCurrent(_, action))

    private def getInternal: F[InternalState[S]] =
      ref.get.flatMap {
        case Some(s) => s.pure[F]
        case None    => loadState.flatTap(s => ref.set(s.some))
      }

    private def loadState: F[InternalState[S]] =
      for {
        effectiveInitial <- snapshotStore.getValue(key).map(_.getOrElse(InternalState(initial, 0)))
        out <- journal
                .foldById(key, effectiveInitial.version + 1, effectiveInitial)(effectiveUpdate)
                .flatMap {
                  case Next(x) => x.pure[F]
                  case Impossible =>
                    F.raiseError[InternalState[S]](
                      BehaviorFailure
                        .illegalFold(key.toString)
                    )
                }
      } yield out

    private def runCurrent[A](current: InternalState[S], action: ActionT[F, S, E, A]): F[A] = {
      def appendEvents(es: NonEmptyChain[E]) =
        journal.append(key, current.version + 1, es)

      def snapshotIfNeeded(next: InternalState[S]) =
        if ((current.version to next.version).exists(needsSnapshot))
          snapshotStore.setValue(key, next)
        else
          ().pure[F]

      for {
        result <- action
                   .xmapState[InternalState[S]](_.withEntityState(_))(_.entityState)
                   .product(ActionT.read)
                   .run(current, effectiveUpdate)
        out <- result match {
                case Next((events, (a, next))) =>
                  NonEmptyChain.fromChain(events) match {
                    case Some(es) =>
                      for {
                        _ <- appendEvents(es)
                        _ <- snapshotIfNeeded(next)
                        _ <- setInternal(next)
                      } yield a
                    case None =>
                      a.pure[F]
                  }
                case Impossible =>
                  F.raiseError[A](BehaviorFailure.illegalFold(key.toString))
              }
      } yield out
    }

    private def setInternal(s: InternalState[S]): F[Unit] =
      ref.set(s.some)
  }

  object DefaultActionRunner {
    def create[F[_]: Sync, K, S, E](
      key: K,
      initial: S,
      update: (S, E) => Folded[S],
      journal: EventJournal[F, K, E],
      snapshotting: Option[Snapshotting[F, K, S]]
    ): F[ActionRunner[F, S, E]] =
      Ref[F]
        .of(none[InternalState[S]])
        .map(ref => new DefaultActionRunner(key, initial, update, journal, snapshotting, ref))
  }

  sealed abstract class Entity[K, M[_[_]], F[_], R] {
    def apply(k: K): M[λ[x => F[Either[R, x]]]]
  }

  object Entity {
    private final class EntityImpl[K, M[_[_]], F[_], R](mfr: K => M[EitherT[F, R, ?]])(
      implicit M: FunctorK[M]
    ) extends Entity[K, M, F, R] {
      def apply(k: K): M[λ[x => F[Either[R, x]]]] =
        M.mapK(mfr(k))(new (EitherT[F, R, ?] ~> λ[x => F[Either[R, x]]]) {
          override def apply[A](fa: EitherT[F, R, A]): F[Either[R, A]] = fa.value
        })
    }

    def apply[K, M[_[_]]: FunctorK, F[_], R](mfr: K => M[EitherT[F, R, ?]]): Entity[K, M, F, R] =
      new EntityImpl(mfr)

    def fromEitherK[K, M[_[_]]: FunctorK, F[_], R](mfr: K => EitherK[M, F, R]): Entity[K, M, F, R] =
      new Entity[K, M, F, R] {
        override def apply(k: K): M[λ[x => F[Either[R, x]]]] = mfr(k).unwrap
      }
  }

  final case class Snapshotting[F[_], K, S](snapshotEach: Long,
                                            store: KeyValueStore[F, K, InternalState[S]])
  type EntityKey = String
  final case class InternalState[S](entityState: S, version: Long) {
    def withEntityState(s: S): InternalState[S] = copy(entityState = s)
  }

  sealed abstract class BehaviorFailure extends Throwable with Product with Serializable
  object BehaviorFailure {
    def illegalFold(entityId: EntityKey): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityKey: EntityKey) extends BehaviorFailure
  }

  def apply[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    entityBehavior: EventsourcedBehavior[M, F, S, E],
    journal: EventJournal[F, K, E],
    snapshotting: Option[Snapshotting[F, K, S]] = Option.empty
  ): K => F[M[F]] = { key =>
    for {
      actionRunner <- DefaultActionRunner.create(
                       key,
                       entityBehavior.initial,
                       entityBehavior.update,
                       journal,
                       snapshotting
                     )
    } yield entityBehavior.actions.mapK(actionRunner)
  }
}
