package aecor.tests

import aecor.core.aggregate.serialization.HandleCommandCodec
import aecor.core.aggregate.{CommandId, HandleIdentifiedCommand$}
import akka.actor.ExtendedActorSystem

class CommandMessageCodecSpec extends AkkaSpec {

  val codec = new HandleCommandCodec(system.asInstanceOf[ExtendedActorSystem])

  "CommandMessageCodec" must {
    "be able to encode/decode CommandMessage" in {
      val obj = HandleIdentifiedCommand(CommandId("id"), null)
      val blob = codec.encode(obj)
      val ref = codec.decode(blob, codec.manifest(obj))
      ref.get shouldEqual obj
    }
  }
}
