package akka.persistence.cassandra.journal

import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.runtime.{ Async, Capture, CaptureFuture, EventJournal }
import aecor.aggregate.serialization.{
  DecodingFailure,
  PersistentDecoder,
  PersistentEncoder,
  PersistentRepr
}

import akka.actor.{ ActorSystem, Props }
import akka.pattern._
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.Materializer
import akka.util.Timeout
import cats.data.NonEmptyVector
import cats.{ Functor, Monad }
import cats.implicits._

import scala.concurrent.Future
import scala.concurrent.duration._

object CassandraEventJournal {
  def apply[F[_]: Async: Capture: CaptureFuture: Functor, E: PersistentEncoder: PersistentDecoder](
    system: ActorSystem,
    decodingParallelism: Int
  )(implicit materializer: Materializer): F[EventJournal[F, E]] = Capture[F].capture {
    val actor =
      system.actorOf(Props(new CassandraEventJournalActor[E](system.settings.config)))
    val readJournal =
      PersistenceQuery(system)
        .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    implicit val askTimeout = Timeout(30.seconds)

    new EventJournal[F, E] {
      override def append(id: String,
                          instanceId: UUID,
                          events: NonEmptyVector[EventEnvelope[E]]): F[Unit] =
        CaptureFuture[F].captureF {
          actor ? CassandraEventJournalActor.WriteMessages(id, events, instanceId)
        }.void

      override def foldById[G[_]: Monad, S](id: String,
                                            offset: Long,
                                            zero: S,
                                            step: (S, E) => G[S]): F[G[S]] =
        CaptureFuture[F].captureF {
          readJournal
            .currentEventsByPersistenceId(id, offset, Long.MaxValue)
            .scan((0, Option.empty[akka.persistence.query.EventEnvelope])) {
              case ((idx, _), e) =>
                if (e.sequenceNr == idx + 1) {
                  (idx + 1, Some(e))
                } else {
                  (idx, None)
                }
            }
            .collect {
              case (_, Some(x)) => x
            }
            .mapAsync(decodingParallelism) {
              case akka.persistence.query.EventEnvelope(_, _, _, event: PersistentRepr) =>
                PersistentDecoder[E].decode(event).fold(Future.failed, Future.successful)
              case other =>
                Future.failed(DecodingFailure(s"Unexpected underlying type ${other.event}"))
            }
            .runFold(zero.pure[G]) { (s, e) =>
              s.flatMap(step(_, e))
            }
        }
    }
  }

}
