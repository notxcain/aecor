package aecor.tests

import java.time.{Instant, LocalDateTime}

import aecor.runtime.akkapersistence.serialization.{PersistentDecoder, PersistentEncoder}
import aecor.schedule.ScheduleEvent
import org.scalacheck.Shapeless._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.prop.PropertyChecks

class ScheduleEventCodecSpec extends AkkaSpec with PropertyChecks {
  val encoder = PersistentEncoder[ScheduleEvent]
  val decoder = PersistentDecoder[ScheduleEvent]

  implicit val arbitraryLocalDateTime = Arbitrary(Gen.lzy(Gen.const(LocalDateTime.now())))
  implicit val arbitraryInstant = Arbitrary(Gen.lzy(Gen.const(Instant.now())))

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
