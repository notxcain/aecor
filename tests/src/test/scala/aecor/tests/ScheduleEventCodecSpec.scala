package aecor.tests

import java.time.{ Instant, LocalDateTime }
import java.time.temporal.ChronoUnit

import aecor.old.schedule.ScheduleEvent
import aecor.old.schedule.protobuf.ScheduleEventCodec
import org.scalacheck.Shapeless._
import org.scalacheck.{ Arbitrary, Gen }
import org.scalatest.prop.PropertyChecks

import scala.util.Success

class ScheduleEventCodecSpec extends AkkaSpec with PropertyChecks {
  val codec = ScheduleEventCodec

  implicit val arbitraryLocalDateTime = Arbitrary(Gen.const(LocalDateTime.now().truncatedTo( ChronoUnit.MILLIS )))
  implicit val arbitraryInstant = Arbitrary(Gen.const(Instant.now().truncatedTo( ChronoUnit.MILLIS ) ))

  "ScheduleEventCodec" must {
    "be able to encode and decode ScheduleEvent" in {
      forAll { e: ScheduleEvent =>
        val manifest = codec.manifest(e)
        val bytes = codec.encode(e)
        val decoded = codec.decode(bytes, manifest)

        decoded shouldEqual Success(e)
      }
    }
  }
}
