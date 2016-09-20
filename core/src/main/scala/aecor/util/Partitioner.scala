package aecor.util

import scala.util.hashing.MurmurHash3

object Partitioner {
  def murmur3: Partitioner = new Murmur3Partitioner
}

trait Partitioner {
  def partitionForString(string: String, partitionCount: Int): Int
}

class Murmur3Partitioner extends Partitioner {
  override def partitionForString(string: String, partitionCount: Int): Int = scala.math.abs(MurmurHash3.stringHash(string)) % partitionCount
}
