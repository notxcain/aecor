package aecor.runtime.akkageneric.serialization

import java.nio.ByteBuffer

import aecor.runtime.akkageneric.GenericAkkaRuntime.KeyedCommand
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.{ Command, CommandResult }
import akka.actor.ExtendedActorSystem
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }
import com.google.protobuf.ByteString

import scala.collection.immutable.HashMap

class MessageSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  val KeyedCommandManifest = "A"
  val CommandManifest = "B"
  val CommandResultManifest = "C"

  private val fromBinaryMap =
    HashMap[String, Array[Byte] ⇒ AnyRef](
      KeyedCommandManifest -> keyedCommandFromBinary,
      CommandManifest -> commandFromBinary,
      CommandResultManifest -> commandResultFromBinary
    )

  override def manifest(o: AnyRef): String = o match {
    case KeyedCommand(_, _) => KeyedCommandManifest
    case Command(_)         => CommandManifest
    case CommandResult(_)   => CommandResultManifest
    case x                  => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case Command(bytes) =>
      bytes.array()
    case CommandResult(bytes) =>
      bytes.array()
    case x @ KeyedCommand(_, _) =>
      entityCommandToBinary(x)
    case x => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) => f(bytes)
      case other   => throw new IllegalArgumentException(s"Unknown manifest [$other]")
    }

  private def entityCommandToBinary(a: KeyedCommand): Array[Byte] =
    msg.KeyedCommand(a.key, ByteString.copyFrom(a.bytes)).toByteArray

  private def commandFromBinary(bytes: Array[Byte]): KeyedCommand =
    msg.KeyedCommand.parseFrom(bytes) match {
      case msg.KeyedCommand(key, commandBytes) =>
        KeyedCommand(key, commandBytes.asReadOnlyByteBuffer)
    }

  private def keyedCommandFromBinary(bytes: Array[Byte]): Command =
    Command(ByteBuffer.wrap(bytes))

  private def commandResultFromBinary(bytes: Array[Byte]): CommandResult =
    CommandResult(ByteBuffer.wrap(bytes))
}
