package aecor.tests.e2e

import java.util.UUID

import aecor.aggregate.runtime.EventJournal
import aecor.data.EventTag
import aecor.streaming.{ Committable, ConsumerId }
import aecor.tests.e2e.TestEventJournal.TestEventJournalState
import cats.{ Applicative, Monad, Traverse }
import cats.data.{ NonEmptyVector, StateT }
import cats.implicits._

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

  def eventsByTag(
    tag: EventTag[E],
    consumerId: ConsumerId
  ): FoldableSource[StateT[F, A, ?], StateT[F, A, ?], Committable[StateT[F, A, ?], E]] =
    new FoldableSource[StateT[F, A, ?], StateT[F, A, ?], Committable[StateT[F, A, ?], E]] {
      override def foldM[S](zero: S)(
        step: (S, Committable[StateT[F, A, ?], E]) => StateT[F, A, S]
      ): StateT[F, A, StateT[F, A, S]] =
        for {
          offset0 <- StateT
                      .inspect[F, TestEventJournalState[E], Int](
                        _.getConsumerOffset(tag, consumerId)
                      )
                      .transformS(extract, update)
          result <- StateT
                     .inspect[F, TestEventJournalState[E], StateT[F, A, S]] {
                       _.eventsByTag
                         .getOrElse(tag, Vector.empty)
                         .zipWithIndex
                         .drop(offset0)
                         .map {
                           case (envelope, offset) =>
                             Committable[StateT[F, A, ?], E](
                               () =>
                                 StateT
                                   .modify[F, TestEventJournalState[E]](
                                     _.setConsumerOffset(tag, consumerId, offset + 1)
                                   )
                                   .transformS(extract, update),
                               envelope.event
                             )
                         }
                         .foldM(zero)(step)
                     }
                     .transformS(extract, update)
        } yield result

    }
}

sealed trait FoldableSourceMerge[F[_], G[_]] {
  def apply[E](lhs: FoldableSource[F, G, E], rhs: FoldableSource[F, G, E]): FoldableSource[F, G, E]
}

object FoldableSourceMerge {
  implicit def foldableSourceMergeEq[F[_]: Monad]: FoldableSourceMerge[F, F] =
    new FoldableSourceMerge[F, F] {
      override def apply[E](lhs: FoldableSource[F, F, E],
                            rhs: FoldableSource[F, F, E]): FoldableSource[F, F, E] =
        new FoldableSource[F, F, E] {
          override def foldM[S](zero: S)(step: (S, E) => F[S]): F[F[S]] =
            for {
              c <- lhs
                    .foldM(zero)((s, e) => step(s, e))
                    .flatten
              o <- rhs.foldM(c)(step)
            } yield o

        }
    }
  implicit def foldableSourceMergeGTraverse[F[_]: Monad, G[_]: Traverse: Monad]
    : FoldableSourceMerge[F, G] =
    new FoldableSourceMerge[F, G] {
      override def apply[E](lhs: FoldableSource[F, G, E],
                            rhs: FoldableSource[F, G, E]): FoldableSource[F, G, E] =
        new FoldableSource[F, G, E] {
          override def foldM[S](zero: S)(step: (S, E) => G[S]): F[G[S]] =
            for {
              x <- lhs.foldM(zero)(step)
              y <- Traverse[G].traverse(x)(zero1 => lhs.foldM(zero1)(step))
            } yield y.flatten
        }
    }
}

trait FoldableSource[F[_], G[_], A] { outer =>
  def foldM[S](zero: S)(step: (S, A) => G[S]): F[G[S]]

  def map[B](f: A => B): FoldableSource[F, G, B] = new FoldableSource[F, G, B] {
    override def foldM[S](zero: S)(step: (S, B) => G[S]): F[G[S]] =
      outer.foldM(zero) {
        case (s, e) =>
          step(s, f(e))
      }
  }

  def merge(
    that: FoldableSource[F, G, A]
  )(implicit Merge: FoldableSourceMerge[F, G]): FoldableSource[F, G, A] =
    Merge(this, that)
}

object FoldableSource {
  def empty[F[_]: Applicative, G[_]: Applicative, E]: FoldableSource[F, G, E] =
    new FoldableSource[F, G, E] {
      override def foldM[S](zero: S)(step: (S, E) => G[S]): F[G[S]] = zero.pure[G].pure[F]
    }
}
