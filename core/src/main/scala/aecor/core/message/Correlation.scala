package aecor.core.message

import aecor.core.message.Correlation.CorrelationId

import scala.util.hashing.MurmurHash3


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
  def apply(id: String, numberOfShards: Int): String =  (MurmurHash3.stringHash(id) % numberOfShards).toString
}