package aecor.core.process.serialization

import aecor.core.serialization.akka.CodecSerializer
import akka.actor.ExtendedActorSystem

class ProcessStateChangedSerializer(extendedActorSystem: ExtendedActorSystem)
  extends CodecSerializer(extendedActorSystem, new ProcessStateChangedCodec(extendedActorSystem))
