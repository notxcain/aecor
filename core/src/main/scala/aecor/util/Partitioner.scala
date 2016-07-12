package aecor.util

import scala.util.hashing.MurmurHash3

object Partitioner {
  def paritionForString(string: String, partitionCount: Int): Int = scala.math.abs(MurmurHash3.stringHash(string)) % partitionCount
}
