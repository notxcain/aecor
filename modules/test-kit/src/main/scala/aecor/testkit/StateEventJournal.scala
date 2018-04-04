package aecor.testkit

import aecor.data._
import aecor.testkit.StateEventJournal.State
import cats.data.{ NonEmptyVector }
import cats.implicits._
import cats.mtl.MonadState
import monocle.Lens

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

      val newEventsByTag: Map[EventTag, Vector[EntityEvent[I, E]]] = events.toVector.zipWithIndex
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

  def apply[F[_]: MonadState[?[_], A], K, A, E](
    lens: Lens[A, State[K, E]],
    tagging: Tagging[K]
  ): StateEventJournal[F, K, A, E] =
    new StateEventJournal(lens, tagging)

}

final class StateEventJournal[F[_], K, S, E](lens: Lens[S, State[K, E]], tagging: Tagging[K])(
  implicit MS: MonadState[F, S]
) extends EventJournal[F, K, E]
    with CurrentEventsByTagQuery[F, K, E] {
  private final implicit val monad = MS.monad
  private final val F = lens.transformMonadState(MonadState[F, S])

  override def append(id: K, offset: Long, events: NonEmptyVector[E]): F[Unit] =
    F.modify(_.appendEvents(id, offset, events.map(e => TaggedEvent(e, tagging.tag(id)))))

  override def foldById[A](id: K, offset: Long, zero: A)(f: (A, E) => Folded[A]): F[Folded[A]] =
    F.inspect(
        _.eventsById
          .get(id)
          .map(_.drop(offset.toInt))
          .getOrElse(Vector.empty)
      )
      .map(_.foldM(zero)(f))

  override def currentEventsByTag(tag: EventTag,
                                  consumerId: ConsumerId): Processable[F, EntityEvent[K, E]] =
    new Processable[F, EntityEvent[K, E]] {
      override def process(f: EntityEvent[K, E] => F[Unit]): F[Unit] =
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

final case class TaggedEvent[E](event: E, tags: Set[EventTag])
