package aecor.tests

import aecor.core.aggregate.AggregateResponse
import aecor.core.aggregate.serialization.AggregateResponseCodec
import akka.actor.ExtendedActorSystem

class AggregateResponseCodecSpec extends AkkaSpec {
  val codec = new AggregateResponseCodec(system.asInstanceOf[ExtendedActorSystem])
  "AggregateResponseCodec" must {
    "be able to encode/decode AggregateResponse when Accepted" in {
      val obj = AggregateResponse.Accepted
      val blob = codec.encode(obj)
      val ref = codec.decode(blob, codec.manifest(obj))
      ref.get shouldEqual obj
    }
    "be able to encode/decode AggregateResponse when Rejected" in {
      val obj = AggregateResponse.Rejected(null)
      val blob = codec.encode(obj)
      val ref = codec.decode(blob, codec.manifest(obj))
      ref.get shouldEqual obj
    }
  }
}
