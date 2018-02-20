package aecor.testkit

import aecor.data._
import aecor.testkit.Eventsourced.EventEnvelope
import aecor.testkit.StateEventJournal.State
import cats.data.{ NonEmptyVector, StateT }
import cats.implicits._
import cats.{ Applicative, Monad }

object StateEventJournal {
  case class State[I, E](eventsById: Map[I, Vector[EventEnvelope[I, E]]],
                         eventsByTag: Map[EventTag, Vector[EventEnvelope[I, E]]],
                         consumerOffsets: Map[(EventTag, ConsumerId), Int]) {
    def getConsumerOffset(tag: EventTag, consumerId: ConsumerId): Int =
      consumerOffsets.getOrElse(tag -> consumerId, 0)

    def setConsumerOffset(tag: EventTag, consumerId: ConsumerId, offset: Int): State[I, E] =
      copy(consumerOffsets = consumerOffsets.updated(tag -> consumerId, offset))

    def appendEvents(id: I, events: NonEmptyVector[EventEnvelope[I, E]]): State[I, E] = {
      val updatedEventsById = eventsById
        .updated(id, eventsById.getOrElse(id, Vector.empty) ++ events.toVector)
      val newEventsByTag: Map[EventTag, Vector[EventEnvelope[I, E]]] = events
        .map(id -> _)
        .toVector
        .flatMap {
          case i @ (_, a) =>
            a.tags.toVector.map(_ -> i)
        }
        .groupBy(_._1)
        .mapValues(_.map(_._2._2))
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

  def apply[F[_]: Monad, I, A, E](extract: A => State[I, E],
                                  update: (A, State[I, E]) => A): StateEventJournal[F, I, A, E] =
    new StateEventJournal(extract, update)
}

class StateEventJournal[F[_]: Monad, I, A, E](extract: A => State[I, E],
                                              update: (A, State[I, E]) => A)
    extends EventJournal[StateT[F, A, ?], I, EventEnvelope[I, E]] {
  override def append(id: I, events: NonEmptyVector[EventEnvelope[I, E]]): StateT[F, A, Unit] =
    StateT
      .modify[F, State[I, E]](_.appendEvents(id, events))
      .transformS(extract, update)

  override def foldById[S](id: I, offset: Long, zero: S)(
    f: (S, EventEnvelope[I, E]) => Folded[S]
  ): StateT[F, A, Folded[S]] =
    StateT
      .inspect[F, State[I, E], Vector[EventEnvelope[I, E]]](
        _.eventsById
          .get(id)
          .map(_.drop(offset.toInt))
          .getOrElse(Vector.empty)
      )
      .map(_.foldM(zero)(f))
      .transformS(extract, update)

  def eventsByTag(tag: EventTag,
                  consumerId: ConsumerId): Processable[StateT[F, A, ?], EntityEvent[I, E]] =
    new Processable[StateT[F, A, ?], EntityEvent[I, E]] {
      override def process(f: EntityEvent[I, E] => StateT[F, A, Unit]): StateT[F, A, Unit] =
        for {
          offset0 <- StateT
                      .inspect[F, State[I, E], Int](_.getConsumerOffset(tag, consumerId))
                      .transformS(extract, update)
          result <- StateT
                     .inspect[F, State[I, E], Vector[(EventEnvelope[I, E], Int)]] {
                       _.eventsByTag
                         .getOrElse(tag, Vector.empty)
                         .zipWithIndex
                         .drop(offset0)
                     }
                     .transformS(extract, update)

          _ <- result.traverse {
                case (i, offset) =>
                  for {
                    _ <- f(i.entityEvent)
                    _ <- StateT
                          .modify[F, State[I, E]](_.setConsumerOffset(tag, consumerId, offset + 1))
                          .transformS(extract, update)
                  } yield ()
              }
        } yield ()
    }

}

trait Processable[F[_], A] { outer =>
  def process(f: A => F[Unit]): F[Unit]
  def map[B](f: A => B): Processable[F, B] =
    new Processable[F, B] {
      override def process(f0: (B) => F[Unit]): F[Unit] = outer.process(a => f0(f(a)))
    }
  def merge(that: Processable[F, A])(implicit F: Applicative[F]): Processable[F, A] =
    new Processable[F, A] {
      override def process(f: (A) => F[Unit]): F[Unit] =
        F.map2(outer.process(f), that.process(f))((_, _) => ())
    }
}

object Processable {
  def empty[F[_]: Applicative, A]: Processable[F, A] = new Processable[F, A] {
    override def process(f: (A) => F[Unit]): F[Unit] = ().pure[F]
  }
}
