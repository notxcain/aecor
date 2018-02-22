package aecor.testkit

import aecor.data._
import aecor.testkit.StateEventJournal.State
import cats.data.{ NonEmptyVector }
import cats.implicits._
import cats.mtl.MonadState
import cats.Monad

object StateEventJournal {
  case class State[I, E](eventsById: Map[I, Vector[E]],
                         eventsByTag: Map[EventTag, Vector[EntityEvent[I, E]]],
                         consumerOffsets: Map[(EventTag, ConsumerId), Int]) {
    def getConsumerOffset(tag: EventTag, consumerId: ConsumerId): Int =
      consumerOffsets.getOrElse(tag -> consumerId, 0)

    def setConsumerOffset(tag: EventTag, consumerId: ConsumerId, offset: Int): State[I, E] =
      copy(consumerOffsets = consumerOffsets.updated(tag -> consumerId, offset))

    def appendEvents(id: I, offset: Long, events: NonEmptyVector[TaggedEvent[E]]): State[I, E] = {
      val updatedEventsById = eventsById
        .updated(id, eventsById.getOrElse(id, Vector.empty) ++ events.map(_.event).toVector)

      val newEventsByTag: Map[EventTag, Vector[EntityEvent[I, E]]] = events
        .toVector
        .zipWithIndex
        .flatMap {
          case (e, idx) =>
            e.tags.toVector.map(t => t -> EntityEvent(id, idx + offset, e.event))
        }
        .groupBy(_._1)
        .mapValues(_.map(_._2))
      copy(
        eventsById = updatedEventsById,
        eventsByTag =
          eventsByTag |+| newEventsByTag
      )
    }
  }

  object State {
    def init[I, E]: State[I, E] = State(Map.empty, Map.empty, Map.empty)
  }

  def apply[F[_]: Monad: MonadState[?[_], A], I, A, E](
    lens: Lens[A, State[I, E]]
  ): StateEventJournal[F, I, A, E] =
    new StateEventJournal(lens)
}

class StateEventJournal[F[_]: MonadState[?[_], S]: Monad, I, S, E](lens: Lens[S, State[I, E]]) extends EventJournal[F, I, E] {
  val F = lens.transformMonadState(MonadState[F, S])

  override def append(id: I, offset: Long, events: NonEmptyVector[TaggedEvent[E]]): F[Unit] =
    F.modify(_.appendEvents(id, offset, events))

  override def foldById[A](id: I, offset: Long, zero: A)(
    f: (A, E) => Folded[A]
  ): F[Folded[A]] =
    F.inspect(
      _.eventsById
        .get(id)
        .map(_.drop(offset.toInt))
        .getOrElse(Vector.empty)
    )
    .map(_.foldM(zero)(f))

  override def currentEventsByTag(tag: EventTag,
                                  consumerId: ConsumerId): Processable[F, EntityEvent[I, E]] =
    new Processable[F, EntityEvent[I, E]] {
      override def process(f: EntityEvent[I, E] => F[Unit]): F[Unit] =
        for {
          offset0 <- F.inspect(_.getConsumerOffset(tag, consumerId))
          result <- F.inspect(
                      _.eventsByTag
                        .getOrElse(tag, Vector.empty)
                        .zipWithIndex
                        .drop(offset0)
                    )
          _ <- result.traverse {
                case (i, offset) =>
                  for {
                    _ <- f(i)
                    _ <- F.modify(_.setConsumerOffset(tag, consumerId, offset + 1))
                  } yield ()
              }
        } yield ()
    }

}