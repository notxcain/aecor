package aecor.testkit

import aecor.data.{ ConsumerId, EventTag, Folder }
import aecor.testkit.Eventsourced.EventEnvelope
import aecor.testkit.StateEventJournal.State
import cats.data.{ NonEmptyVector, StateT }
import cats.implicits._
import cats.{ Applicative, Monad }

object StateEventJournal {
  case class State[E](eventsById: Map[String, Vector[EventEnvelope[E]]],
                      eventsByTag: Map[EventTag, Vector[EventEnvelope[E]]],
                      consumerOffsets: Map[(EventTag, ConsumerId), Int]) {
    def getConsumerOffset(tag: EventTag, consumerId: ConsumerId): Int =
      consumerOffsets.getOrElse(tag -> consumerId, 0)

    def setConsumerOffset(tag: EventTag, consumerId: ConsumerId, offset: Int): State[E] =
      copy(consumerOffsets = consumerOffsets.updated(tag -> consumerId, offset))

    def appendEvents(id: String, events: NonEmptyVector[EventEnvelope[E]]): State[E] =
      copy(
        eventsById = eventsById
          .updated(id, eventsById.getOrElse(id, Vector.empty) ++ events.toVector),
        eventsByTag =
          eventsByTag |+| events.toVector
            .flatMap { env =>
              env.tags.toVector.map(t => (t, env))
            }
            .groupBy(_._1)
            .mapValues(_.map(_._2))
      )
  }

  object State {
    def init[E]: State[E] = State(Map.empty, Map.empty, Map.empty)
  }

  def apply[F[_]: Monad, A, E](extract: A => State[E],
                               update: (A, State[E]) => A): StateEventJournal[F, A, E] =
    new StateEventJournal(extract, update)
}

class StateEventJournal[F[_]: Monad, A, E](extract: A => State[E], update: (A, State[E]) => A)
    extends EventJournal[StateT[F, A, ?], String, EventEnvelope[E]] {
  override def append(id: String, events: NonEmptyVector[EventEnvelope[E]]): StateT[F, A, Unit] =
    StateT
      .modify[F, State[E]](_.appendEvents(id, events))
      .transformS(extract, update)

  override def foldById[G[_]: Monad, S](
    id: String,
    offset: Long,
    folder: Folder[G, EventEnvelope[E], S]
  ): StateT[F, A, G[S]] =
    StateT
      .inspect[F, State[E], Vector[EventEnvelope[E]]](
        _.eventsById
          .get(id)
          .map(_.drop(offset.toInt))
          .getOrElse(Vector.empty)
      )
      .map(folder.consume(_))
      .transformS(extract, update)

  def eventsByTag(tag: EventTag, consumerId: ConsumerId): Processable[StateT[F, A, ?], E] =
    new Processable[StateT[F, A, ?], E] {
      override def process(f: (E) => StateT[F, A, Unit]): StateT[F, A, Unit] =
        for {
          offset0 <- StateT
                      .inspect[F, State[E], Int](_.getConsumerOffset(tag, consumerId))
                      .transformS(extract, update)
          result <- StateT
                     .inspect[F, State[E], Vector[(EventEnvelope[E], Int)]] {
                       _.eventsByTag
                         .getOrElse(tag, Vector.empty)
                         .zipWithIndex
                         .drop(offset0)
                     }
                     .transformS(extract, update)

          _ <- result.traverse {
                case (envelope, offset) =>
                  for {
                    _ <- f(envelope.event)
                    _ <- StateT
                          .modify[F, State[E]](_.setConsumerOffset(tag, consumerId, offset + 1))
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
