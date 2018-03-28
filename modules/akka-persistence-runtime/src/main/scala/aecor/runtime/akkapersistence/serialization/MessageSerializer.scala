package aecor.runtime.akkapersistence.serialization

import java.nio.ByteBuffer

import aecor.runtime.akkapersistence.AkkaPersistenceRuntime.EntityCommand
import aecor.runtime.akkapersistence.AkkaPersistenceRuntimeActor.HandleCommand
import akka.actor.ExtendedActorSystem
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import com.google.protobuf.ByteString

import scala.collection.immutable._

class MessageSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  val HandleCommandManifest = "A"
  val EntityCommandManifest = "B"

  private val fromBinaryMap =
    HashMap[String, Array[Byte] ⇒ AnyRef](
      HandleCommandManifest -> handleCommandFromBinary,
      EntityCommandManifest -> entityCommandFromBinary
    )

  override def manifest(o: AnyRef): String = o match {
    case HandleCommand(_)    => HandleCommandManifest
    case EntityCommand(_, _) => EntityCommandManifest
    case x                   => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case x @ HandleCommand(_) =>
      x.commandBytes.array()
    case x @ EntityCommand(_, _) =>
      entityCommandToBinary(x)
    case x => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) => f(bytes)
      case other   => throw new IllegalArgumentException(s"Unknown manifest [$other]")
    }

  private def entityCommandToBinary(a: EntityCommand): Array[Byte] =
    msg.EntityCommand(a.entityKey, ByteString.copyFrom(a.commandBytes)).toByteArray

  private def entityCommandFromBinary(bytes: Array[Byte]): EntityCommand =
    msg.EntityCommand.parseFrom(bytes) match {
      case msg.EntityCommand(entityId, commandBytes) =>
        EntityCommand(entityId, commandBytes.asReadOnlyByteBuffer)
    }

  private def handleCommandFromBinary(bytes: Array[Byte]): HandleCommand =
    HandleCommand(ByteBuffer.wrap(bytes))
}
