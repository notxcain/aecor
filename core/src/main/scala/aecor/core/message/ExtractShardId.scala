package aecor.core.message

import aecor.util.Partitioner

object ExtractShardId {
  private val partitioner = Partitioner.murmur3
  def apply(id: String, numberOfShards: Int): String = partitioner.partitionForString(id, numberOfShards).toString
}
