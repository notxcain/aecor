package aecor.runtime.eventsourced

import aecor.data.Folded.Next
import aecor.data.{ ActionT, EntityEvent, Folded }
import aecor.runtime.EventJournal
import aecor.runtime.Eventsourced.Versioned
import cats.data.{ Chain, NonEmptyChain }
import cats.effect.Sync
import cats.implicits._

trait EventsourcedState[F[_], K, S, E] {
  def recover(key: K, snapshot: Option[Versioned[S]]): F[Versioned[S]]
  def run[A](key: K, state: Versioned[S], action: ActionT[F, S, E, A]): F[(Versioned[S], A)]
}

object EventsourcedState {
  def apply[F[_]: Sync, K, E, S](initialState: S,
                                 update: (S, E) => Folded[S],
                                 journal: EventJournal[F, K, E]): EventsourcedState[F, K, S, E] =
    new DefaultEventsourcedState(initialState, update, journal)
}

final class DefaultEventsourcedState[F[_], K, E, S](
  initialState: S,
  update: (S, E) => Folded[S],
  journal: EventJournal[F, K, E]
)(implicit F: Sync[F])
    extends EventsourcedState[F, K, S, E] {

  override def recover(key: K, snapshot: Option[Versioned[S]]): F[Versioned[S]] = {
    val from = snapshot.getOrElse(Versioned.zero(initialState))
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
  }

  override def run[A](key: K,
                      state: Versioned[S],
                      action: ActionT[F, S, E, A]): F[(Versioned[S], A)] =
    for {
      result <- action.zipWithRead.run(state.value, update)
      (es, (nextState, a)) <- result match {
                               case Next(a) => a.pure[F]
                               case Folded.Impossible =>
                                 F.raiseError[(Chain[E], (S, A))](
                                   new IllegalArgumentException(
                                     s"Failed to run action [$action] against state [$state]"
                                   )
                                 )
                             }
      _ <- NonEmptyChain
            .fromChain(es)
            .traverse_(nes => journal.append(key, state.version + 1, nes))
    } yield (Versioned(state.version + es.size, nextState), a)
}
