package aecor.core.message

import aecor.core.message.Correlation.CorrelationId
import aecor.util.Partitioner

object Correlation {
  type CorrelationId = String
  def instance[A](f: A => String): Correlation[A] = new Correlation[A] {
    override def apply(a: A): CorrelationId = f(a)
  }
}

trait Correlation[A] {
  def apply(a: A): CorrelationId
}

object ExtractShardId {
  def apply(id: String, numberOfShards: Int): String = Partitioner.paritionForString(id, numberOfShards).toString
}