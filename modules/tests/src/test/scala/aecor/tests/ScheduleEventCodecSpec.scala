package aecor.tests

import java.time.temporal.{ChronoField, Temporal}
import java.time.{Instant, LocalDateTime}

import aecor.runtime.akkapersistence.serialization.{PersistentDecoder, PersistentEncoder}
import aecor.schedule.ScheduleEvent
import org.scalacheck.ScalacheckShapeless._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.prop.PropertyChecks

class ScheduleEventCodecSpec extends AkkaSpec with PropertyChecks {
  val encoder = PersistentEncoder[ScheduleEvent]
  val decoder = PersistentDecoder[ScheduleEvent]

  // OpenJDK 9+ offers more precise system clock than millisecond.
  // https://bugs.openjdk.java.net/browse/JDK-8068730
  def dropBelowMillis[A <: Temporal](t: A): A =
    t.`with`(ChronoField.MICRO_OF_SECOND, t.getLong(ChronoField.MILLI_OF_SECOND) * 1000L).asInstanceOf[A]

  implicit val arbitraryLocalDateTime = Arbitrary(Gen.lzy(Gen.const(dropBelowMillis(LocalDateTime.now()))))
  implicit val arbitraryInstant       = Arbitrary(Gen.lzy(Gen.const(dropBelowMillis(Instant.now()))))

  "ScheduleEventCodec" must {
    "be able to encode and decode ScheduleEvent" in {
      forAll { e: ScheduleEvent =>
        val repr = encoder.encode(e)
        val decoded = decoder.decode(repr)
        decoded shouldEqual Right(e)
      }
    }
  }
}
