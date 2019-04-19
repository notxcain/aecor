package aecor.runtime.eventsourced
import aecor.data.Folded.Next
import aecor.data.{ ActionT, EntityEvent, Folded }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.{ Snapshotting, Versioned }
import aecor.runtime.eventsourced.Stage.X
import cats.data.{ Chain, Kleisli, NonEmptyChain }
import cats.effect.Sync
import cats.implicits._
import cats.~>

object Stage {
  type Middleware[F[_], G[_], A, B, C, D] = Kleisli[F, A, B] => Kleisli[G, C, D]
  type Stage1[F[_], K, S, E] = Kleisli[F, K, ActionT[F, S, E, ?] ~> F]
  trait X[F[_], S, E] {
    def recoverAndRun[A](from: Versioned[S], action: ActionT[F, S, E, A]): F[A]
  }
}

final class BasicStateStrategyBuilder[F[_], K, S, E](
  update: (S, E) => Folded[S],
  journal: EventJournal[F, K, E]
)(implicit F: Sync[F])
    extends StateStrategyBuilder[K, F, S, E] {

  override def x(key: K): F[Stage.X[F, S, E]] = F.pure {
    new X[F, S, E] {

      def recoverState(from: Versioned[S]): F[Versioned[S]] =
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
            case (other, _) => other
          }
          .takeWhile(_.isRight, takeFailure = true)
          .compile
          .last
          .flatMap {
            case Some(x) => F.fromEither(x)
            case None    => F.pure(from)
          }

      def runAction[A](current: Versioned[S], action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
        for {
          result <- action.zipWithRead.run(current.value, update)
          (es, (nextState, a)) <- result match {
                                   case Next(a) => a.pure[F]
                                   case Folded.Impossible =>
                                     F.raiseError[(Chain[E], (S, A))](
                                       new IllegalArgumentException(
                                         s"Failed to run action [$action] against state [$current]"
                                       )
                                     )
                                 }
          _ <- NonEmptyChain
                .fromChain(es)
                .traverse_(nes => journal.append(key, current.version + 1, nes))
        } yield (Versioned(current.version + es.size, nextState), a)

      override def recoverAndRun[A](from: Versioned[S], action: ActionT[F, S, E, A]): F[A] =
        recoverState(from).flatMap { recovered =>
          runAction(recovered, action).map(_._2)
        }
    }
  }

  override def create(key: K): F[EntityStateStrategy[F, S, E]] = F.pure {
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
            case (other, _) => other
          }
          .takeWhile(_.isRight, takeFailure = true)
          .compile
          .last
          .flatMap {
            case Some(x) => F.fromEither(x)
            case None    => F.pure(from)
          }

      override def run[A](current: Versioned[S],
                          action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
        for {
          result <- action.zipWithRead.run(current.value, update)
          (es, (nextState, a)) <- result match {
                                   case Next(a) => a.pure[F]
                                   case Folded.Impossible =>
                                     F.raiseError[(Chain[E], (S, A))](
                                       new IllegalArgumentException(
                                         s"Failed to run action [$action] against state [$current]"
                                       )
                                     )
                                 }
          _ <- NonEmptyChain
                .fromChain(es)
                .traverse_(nes => journal.append(key, current.version + 1, nes))
        } yield (Versioned(current.version + es.size, nextState), a)
    }
  }

  def withSnapshotting(
    snapshotting: Snapshotting[F, K, S]
  ): SnapshottingStateStrategyBuilder[F, K, S, E] =
    new SnapshottingStateStrategyBuilder(snapshotting, this)

  def withInmemCache(implicit F: Sync[F]): InMemCachingStateStrategyBuilder[K, F, S, E] =
    InMemCachingStateStrategyBuilder(this)
}

object BasicStateStrategyBuilder {
  def apply[F[_], K, S, E](update: (S, E) => Folded[S], journal: EventJournal[F, K, E])(
    implicit F: Sync[F]
  ) = new BasicStateStrategyBuilder(update, journal)
}
