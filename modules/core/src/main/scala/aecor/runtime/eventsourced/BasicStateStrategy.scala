package aecor.runtime.eventsourced
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ ActionT, EntityEvent, Folded }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ BehaviorFailure, Snapshotting, Versioned }
import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.implicits._
import cats.~>

final class BasicStateStrategy[F[_], K, S, E](update: (S, E) => Folded[S],
                                              journal: EventJournal[F, K, E])(implicit F: Sync[F])
    extends StateStrategy[K, F, S, E] {

  override def focus(key: K): F[EntityStateStrategy[F, S, E]] = F.pure {
    new EntityStateStrategy[F, S, E] {

      override def recoverState(from: Versioned[S]): F[Versioned[S]] =
        journal
          .loadEvents(key, from.version)
          .scan(from.asRight[Throwable]) {
            case (Right(x @ Versioned(version, value)), ee @ EntityEvent(_, _, event)) =>
              update(value, event) match {
                case Next(a) =>
                  Versioned(version + 1, a).asRight[Throwable]
                case Folded.Impossible =>
                  new IllegalStateException(s"Failed to apply event [$ee] to [$x]")
                    .asLeft[Versioned[S]]
              }

          }
          .takeWhile(_.isRight, takeFailure = true)
          .compile
          .last
          .flatMap {
            case Some(x) => F.fromEither(x)
            case None    => F.pure(from)
          }

      def runAction[A](current: Versioned[S], action: ActionT[F, S, E, A]): F[(Versioned[S], A)] = {
        val unfold = Lambda[Folded ~> F] {
          case Next(x) => x.pure[F]
          case Impossible =>
            F.raiseError(
              BehaviorFailure
                .illegalFold(key.toString)
            )
        }
        for {
          result <- action.zipWithRead.run(current.value, update)
          (es, (nextState, a)) <- unfold(result)
          _ <- NonEmptyChain
                .fromChain(es)
                .traverse_(nes => journal.append(key, current.version + 1, nes))
        } yield (Versioned(current.version + es.size, nextState), a)
      }

      override def updateState(current: Versioned[S], es: NonEmptyChain[E]): F[Versioned[S]] =
        es.foldLeftM(current.value)(update)
          .map(Versioned(current.version + es.size, _))
          .fold(
            F.raiseError[Versioned[S]](
              new IllegalArgumentException(s"Failed to update state [$current] with events [$es]")
            )
          )(_.pure[F])
          .flatTap(_ => journal.append(key, current.version + 1, es))
    }
  }

  def withSnapshotting(snapshotting: Snapshotting[F, K, S]): SnapshottingStateStrategy[F, K, S, E] =
    new SnapshottingStateStrategy(snapshotting, this)

  def withInmemCache(implicit F: Sync[F]): InMemCachingStateStrategy[K, F, S, E] =
    InMemCachingStateStrategy(this)
}

object BasicStateStrategy {
  def apply[F[_], K, S, E](update: (S, E) => Folded[S], journal: EventJournal[F, K, E])(
    implicit F: Sync[F]
  ) = new BasicStateStrategy(update, journal)
}
