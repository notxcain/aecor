package aecor.core.streaming

import aecor.core.aggregate._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

import scala.reflect.ClassTag

trait AggregateJournal {
  trait MkCommittableEventSource[A] {
    def apply[E](consumerId: String)
      (implicit name: AggregateName[A],
        contract: EventContract.Aux[A, E],
        E: ClassTag[E]
      ): Source[CommittableJournalEntry[AggregateEvent[E]], NotUsed]
  }

  def committableEventSourceFor[A]: MkCommittableEventSource[A]
}

object AggregateJournal {
  def apply(actorSystem: ActorSystem, offsetStore: OffsetStore): AggregateJournal =
    new CassandraAggregateJournal(actorSystem, offsetStore)
}
