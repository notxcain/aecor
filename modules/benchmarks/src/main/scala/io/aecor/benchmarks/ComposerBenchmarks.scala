package io.aecor.benchmarks

// Must not be in default package
import java.util.concurrent.TimeUnit

import aecor.data.Composer
import org.openjdk.jmh.annotations._

/* Default settings for benchmarks in this class */
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@State(Scope.Benchmark)
class ComposerBenchmarks {

  val components = List(
    "AF027A41-F9FD-44D7-8A68-F059955EE208",
    "33853322-9C96-4001-B760-B47FB6669375",
    "1155C532-A7F4-4262-A651-873905871952"
  )

  val lengthHintedEncoder = Composer.WithLengthHint('=')
  val lengthHintedResult = lengthHintedEncoder(components)
  println(lengthHintedResult)

  val separatedEncoder = Composer.WithSeparator('-')
  val separatedResult = separatedEncoder(components)
  println(separatedResult)

  @Benchmark
  def lengthHintedEncoderEncode(): Unit = {
    lengthHintedEncoder.apply(components)
    ()
  }

  @Benchmark
  def lengthHintedEncoderDecode(): Unit = {
    lengthHintedEncoder.unapply(lengthHintedResult)
    ()
  }

  @Benchmark
  def separatedEncoderEncode(): Unit = {
    separatedEncoder(components)
    ()
  }

  @Benchmark
  def separatedEncoderDecode(): Unit = {
    separatedEncoder.unapply(separatedResult)
    ()
  }
}
