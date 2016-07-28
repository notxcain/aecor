package aecor.tests

import java.time.Instant

import aecor.core.aggregate.serialization.AggregateEventCodec
import aecor.core.aggregate.{AggregateEvent, CommandId, EventId}
import akka.actor.ExtendedActorSystem

class AggregateEventCodecSpec extends AkkaSpec {

  val codec = new AggregateEventCodec(system.asInstanceOf[ExtendedActorSystem])

  "AggregateEventCodec" must {
    "be able to encode/decode AggregateEvent" in {
      val obj = AggregateEvent(EventId("id"), null, Instant.now(), CommandId("causedBy"))
      val blob = codec.encode(obj)
      val ref = codec.decode(blob, codec.manifest(obj))
      ref.get shouldEqual obj
    }
  }
}
