package aecor.core.message

import aecor.core.entity.MarkEventAsPublished
import aecor.core.message.Correlation.CorrelationId
import akka.cluster.sharding.ShardRegion

import scala.reflect.ClassTag
import scala.util.hashing.MurmurHash3


object Correlation {
  type CorrelationId = String
  def instance[A](f: A => String): Correlation[A] = new Correlation[A] {
    override def apply(a: A): CorrelationId = f(a)
  }
  def extractEntityId[C: ClassTag](implicit correlation: Correlation[C]): ShardRegion.ExtractEntityId = {
    case m @ Message(_, c: C, _) ⇒ (correlation(c), m)
    case m @ Message(_, c: MarkEventAsPublished, _) => (c.entityId, c)
  }

  def extractShardId[C: ClassTag](numberOfShards: Int)(implicit correlation: Correlation[C]): ShardRegion.ExtractShardId = {
    case m @ Message(_, c: C, _) => (MurmurHash3.stringHash(correlation(c)) % numberOfShards).toString
    case m @ Message(_, c: MarkEventAsPublished, _) => (MurmurHash3.stringHash(c.entityId) % numberOfShards).toString
  }
}

trait Correlation[A] {
  def apply(a: A): CorrelationId
}
