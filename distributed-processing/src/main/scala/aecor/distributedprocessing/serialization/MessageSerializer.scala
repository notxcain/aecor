package aecor.distributedprocessing.serialization

import aecor.distributedprocessing.DistributedProcessingWorker.KeepRunning
import akka.actor.ExtendedActorSystem
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

class MessageSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {
  val KeepRunningManifest = "A"
  override def manifest(o: AnyRef): String = o match {
    case KeepRunning(_) => KeepRunningManifest
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case KeepRunning(workerId) => msg.KeepRunning(workerId).toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    manifest match {
      case KeepRunningManifest =>
        KeepRunning(msg.KeepRunning.parseFrom(bytes).workerId)
    }
}
