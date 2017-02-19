package akka.persistence.cassandra.journal

import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.runtime.{ Async, EventJournal }
import aecor.aggregate.serialization.{
  DecodingFailure,
  PersistentDecoder,
  PersistentEncoder,
  PersistentRepr
}
import aecor.data.Folded
import akka.actor.{ ActorSystem, Props }
import akka.pattern._
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.Materializer
import akka.util.Timeout
import cats.data.NonEmptyVector

import scala.concurrent.Future
import scala.concurrent.duration._

class CassandraEventJournal[E: PersistentEncoder: PersistentDecoder, F[_]: Async](
  system: ActorSystem,
  decodingParallelism: Int
)(implicit materializer: Materializer)
    extends EventJournal[E, F] {
  private val actor =
    system.actorOf(Props(new CassandraEventJournalActor[E](system.settings.config)))
  private val readJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
  implicit val askTimeout = Timeout(30.seconds)

  override def append(id: String,
                      instanceId: UUID,
                      events: NonEmptyVector[EventEnvelope[E]]): F[Unit] =
    Async[F].capture {
      (actor ? CassandraEventJournalActor.WriteMessages(id, events, instanceId)).mapTo[Unit]
    }

  override def fold[S](id: String, offset: Long, zero: S)(f: (S, E) => Folded[S]): F[Folded[S]] =
    Async[F].capture {
      readJournal
        .currentEventsByPersistenceId(id, offset, Long.MaxValue)
        .mapAsync(decodingParallelism) {
          case akka.persistence.query.EventEnvelope(_, _, _, event: PersistentRepr) =>
            PersistentDecoder[E].decode(event).fold(Future.failed, Future.successful)
          case other =>
            Future.failed(DecodingFailure(s"Unexpected underlying type ${other.event}"))
        }
        .runFold(Folded.next(zero)) { (s, e) =>
          s.flatMap(f(_, e))
        }
    }

}

object CassandraEventJournal {
  def apply[E: PersistentEncoder: PersistentDecoder, F[_]: Async](
    system: ActorSystem,
    decodingParallelism: Int
  )(implicit materializer: Materializer): CassandraEventJournal[E, F] =
    new CassandraEventJournal(system, decodingParallelism)
}
