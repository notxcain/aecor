package aecor.runtime

import aecor.data.Folded.Next
import aecor.data.{ ActionT, EntityEvent, Fold, Folded }
import aecor.runtime.Eventsourced.Versioned
import cats.data.{ Chain, NonEmptyChain }
import cats.effect.Sync
import cats.implicits._

private[aecor] trait EventsourcedState[F[_], K, S, E] {
  def recover(key: K, snapshot: Option[Versioned[S]]): F[Versioned[S]]
  def run[A](key: K, state: Versioned[S], action: ActionT[F, S, E, A]): F[(Versioned[S], A)]
}

private[aecor] object EventsourcedState {
  def apply[F[_]: Sync, K, E, S](fold: Fold[Folded, S, E],
                                 journal: EventJournal[F, K, E]): EventsourcedState[F, K, S, E] =
    new DefaultEventsourcedState(fold, journal)
}

private[aecor] final class DefaultEventsourcedState[F[_], K, E, S](
  fold: Fold[Folded, S, E],
  journal: EventJournal[F, K, E]
)(implicit F: Sync[F])
    extends EventsourcedState[F, K, S, E] {

  private val versionedFold = Versioned.fold(fold)

  override def recover(key: K, snapshot: Option[Versioned[S]]): F[Versioned[S]] = {
    val from = snapshot.getOrElse(versionedFold.initial)
    journal
      .read(key, from.version)
      .scan(from.asRight[Throwable]) {
        case (Right(version), ee @ EntityEvent(_, _, event)) =>
          fold.reduce(version.value, event) match {
            case Next(a) =>
              version.withNextValue(a).asRight[Throwable]
            case Folded.Impossible =>
              new IllegalStateException(s"Failed to apply event [$ee] to [$version]")
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
  }

  override def run[A](key: K,
                      state: Versioned[S],
                      action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
    for {
      result <- action
                 .expand[Versioned[S]]((versioned, s) => versioned.copy(value = s))(_.value)
                 .zipWithRead
                 .run(versionedFold.withInitial(state))
      (es, (a, nextState)) <- result match {
                               case Next(a) => a.pure[F]
                               case Folded.Impossible =>
                                 F.raiseError[(Chain[E], (A, Versioned[S]))](
                                   new IllegalArgumentException(
                                     s"Failed to run action [$action] against state [$state]"
                                   )
                                 )
                             }
      _ <- NonEmptyChain
            .fromChain(es)
            .traverse_(nes => journal.append(key, state.version + 1, nes))
    } yield (nextState, a)
}
