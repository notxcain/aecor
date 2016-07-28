package aecor.tests

import aecor.core.aggregate.serialization.AggregateCommandCodec
import aecor.core.aggregate.{AggregateCommand, CommandId}
import akka.actor.ExtendedActorSystem

class AggregateCommandCodecSpec extends AkkaSpec {

  val codec = new AggregateCommandCodec(system.asInstanceOf[ExtendedActorSystem])

  "CommandMessageCodec" must {
    "be able to encode/decode CommandMessage" in {
      val obj = AggregateCommand(CommandId("id"), null)
      val blob = codec.encode(obj)
      val ref = codec.decode(blob, codec.manifest(obj))
      ref.get shouldEqual obj
    }
  }
}
