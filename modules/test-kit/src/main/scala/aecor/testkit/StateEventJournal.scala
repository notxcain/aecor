package aecor.testkit

import aecor.data._
import aecor.runtime.EventJournal
import aecor.testkit.StateEventJournal.State
import cats.Monad
import cats.data.{ Chain, NonEmptyChain }
import cats.implicits._
import cats.mtl.MonadState
import fs2._
import monocle.Lens

object StateEventJournal {
  final case class State[K, E](eventsByKey: Map[K, Chain[E]],
                               eventsByTag: Map[EventTag, Chain[EntityEvent[K, E]]],
                               consumerOffsets: Map[(EventTag, ConsumerId), Int]) {
    def getConsumerOffset(tag: EventTag, consumerId: ConsumerId): Int =
      consumerOffsets.getOrElse(tag -> consumerId, 0)

    def setConsumerOffset(tag: EventTag, consumerId: ConsumerId, offset: Int): State[K, E] =
      copy(consumerOffsets = consumerOffsets.updated(tag -> consumerId, offset))

    def getEventsByTag(tag: EventTag, offset: Int): Chain[(Int, EntityEvent[K, E])] = {
      val stream = eventsByTag
        .getOrElse(tag, Chain.empty)
        .mapWithIndex((e, i) => (i + 1, e))
        .toList
        .drop(offset)
      Chain.fromSeq(stream)
    }

    def readEvents(key: K): Chain[EntityEvent[K, E]] =
      eventsByKey
        .getOrElse(key, Chain.empty)
        .mapWithIndex((e, idx) => EntityEvent(key, idx + 1L, e))

    def appendEvents(key: K, offset: Long, events: NonEmptyChain[TaggedEvent[E]]): State[K, E] = {
      val updatedEventsById = eventsByKey
        .updated(key, eventsByKey.getOrElse(key, Chain.empty) ++ events.map(_.event).toChain)

      val newEventsByTag: Map[EventTag, Chain[EntityEvent[K, E]]] = events.toChain.zipWithIndex
        .flatMap {
          case (e, idx) =>
            Chain.fromSeq(e.tags.toSeq).map(t => t -> EntityEvent(key, idx + offset, e.event))
        }
        .groupBy(_._1)
        .map {
          case (key, value) =>
            key -> value.map(_._2).toChain
        }

      copy(
        eventsByKey = updatedEventsById,
        eventsByTag =
          eventsByTag |+| newEventsByTag
      )
    }
  }

  object State {
    def init[I, E]: State[I, E] = State(Map.empty, Map.empty, Map.empty)
  }

  def apply[F[_]: Monad: MonadState[?[_], A], K, A, E](
    lens: Lens[A, State[K, E]],
    tagging: Tagging[K]
  ): StateEventJournal[F, K, A, E] =
    new StateEventJournal(lens, tagging)

}

final class StateEventJournal[F[_]: Monad, K, S, E](lens: Lens[S, State[K, E]], tagging: Tagging[K])(
  implicit MS: MonadState[F, S]
) extends EventJournal[F, K, E] {
  private final val F = lens.transformMonadState(MonadState[F, S])

  override def append(key: K, offset: Long, events: NonEmptyChain[E]): F[Unit] =
    F.modify(_.appendEvents(key, offset, events.map(e => TaggedEvent(e, tagging.tag(key)))))

  override def read(key: K, offset: Long): Stream[F, EntityEvent[K, E]] =
    Stream
      .eval(F.inspect(_.readEvents(key)))
      .flatMap(x => Stream(x.toVector: _*))
      .drop(offset)

  def currentEventsByTag(tag: EventTag,
                         consumerId: ConsumerId): Stream[F, Committable[F, EntityEvent[K, E]]] =
    for {
      state <- Stream.eval(F.get)
      committedOffset = state.getConsumerOffset(tag, consumerId)
      result = state.getEventsByTag(tag, committedOffset)
      a <- Stream(result.toVector: _*).map {
            case (offset, e) =>
              Committable(F.modify(_.setConsumerOffset(tag, consumerId, offset)), e)
          }
    } yield a

}

final case class TaggedEvent[E](event: E, tags: Set[EventTag])
