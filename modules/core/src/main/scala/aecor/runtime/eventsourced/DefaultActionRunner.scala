package aecor.runtime.eventsourced
import aecor.data.{ ActionT, Folded }
import aecor.data.Folded.{ Impossible, Next }
import aecor.runtime.Eventsourced.{ BehaviorFailure, InternalState, Snapshotting }
import aecor.runtime.{ EventJournal, NoopKeyValueStore }
import cats.MonadError
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

final class DefaultActionRunner[F[_], K, S, E] private (
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
