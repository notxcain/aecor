package aecor.testkit

import java.util.UUID

import aecor.data.EventJournal.EventEnvelope
import aecor.data.{ EventJournal, EventTag }
import aecor.streaming.ConsumerId
import aecor.testkit.TestEventJournal.TestEventJournalState
import cats.data.{ NonEmptyVector, StateT }
import cats.implicits._
import cats.{ Applicative, Monad }

object TestEventJournal {
  case class TestEventJournalState[E](
    eventsById: Map[String, Vector[EventJournal.EventEnvelope[E]]],
    eventsByTag: Map[EventTag[E], Vector[EventJournal.EventEnvelope[E]]],
    consumerOffsets: Map[(EventTag[E], ConsumerId), Int]
  ) {
    def getConsumerOffset(tag: EventTag[E], consumerId: ConsumerId): Int =
      consumerOffsets.getOrElse(tag -> consumerId, 0)

    def setConsumerOffset(tag: EventTag[E],
                          consumerId: ConsumerId,
                          offset: Int): TestEventJournalState[E] =
      copy(consumerOffsets = consumerOffsets.updated(tag -> consumerId, offset))

    def appendEvents(
      id: String,
      events: NonEmptyVector[EventJournal.EventEnvelope[E]]
    ): TestEventJournalState[E] =
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

  object TestEventJournalState {
    def init[E]: TestEventJournalState[E] = TestEventJournalState(Map.empty, Map.empty, Map.empty)
  }

  def apply[F[_]: Monad, A, E](
    extract: A => TestEventJournalState[E],
    update: (A, TestEventJournalState[E]) => A
  ): TestEventJournal[F, A, E] =
    new TestEventJournal(extract, update)
}

class TestEventJournal[F[_]: Monad, A, E](extract: A => TestEventJournalState[E],
                                          update: (A, TestEventJournalState[E]) => A)
    extends EventJournal[StateT[F, A, ?], E] {
  override def append(id: String,
                      instanceId: UUID,
                      events: NonEmptyVector[EventJournal.EventEnvelope[E]]): StateT[F, A, Unit] =
    StateT
      .modify[F, TestEventJournalState[E]](_.appendEvents(id, events))
      .transformS(extract, update)

  override def foldById[G[_]: Monad, S](id: String,
                                        offset: Long,
                                        zero: S,
                                        step: (S, E) => G[S]): StateT[F, A, G[S]] =
    StateT
      .inspect[F, TestEventJournalState[E], G[S]](
        _.eventsById
          .getOrElse(id, Vector.empty)
          .drop(offset.toInt)
          .map(_.event)
          .foldM(zero)(step)
      )
      .transformS(extract, update)

  def eventsByTag(tag: EventTag[E], consumerId: ConsumerId): Processable[StateT[F, A, ?], E] =
    new Processable[StateT[F, A, ?], E] {
      override def process(f: (E) => StateT[F, A, Unit]): StateT[F, A, Unit] =
        for {
          offset0 <- StateT
                      .inspect[F, TestEventJournalState[E], Int](
                        _.getConsumerOffset(tag, consumerId)
                      )
                      .transformS(extract, update)
          result <- StateT
                     .inspect[F, TestEventJournalState[E], Vector[(EventEnvelope[E], Int)]] {
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
                          .modify[F, TestEventJournalState[E]](
                            _.setConsumerOffset(tag, consumerId, offset + 1)
                          )
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
