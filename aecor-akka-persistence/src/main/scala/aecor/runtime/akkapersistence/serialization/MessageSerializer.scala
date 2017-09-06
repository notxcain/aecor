package aecor.runtime.akkapersistence.serialization

import aecor.runtime.akkapersistence.AkkaPersistenceRuntimeActor.HandleCommand
import akka.actor.ExtendedActorSystem
import akka.serialization.{
  BaseSerializer,
  Serialization,
  SerializationExtension,
  SerializerWithStringManifest
}
import com.google.protobuf.ByteString

import scala.collection.immutable._

class MessageSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  @volatile
  private var ser: Serialization = _
  def serialization: Serialization = {
    if (ser == null) ser = SerializationExtension(system)
    ser
  }

  val HandleCommandManifest = "A"

  private val fromBinaryMap =
    HashMap[String, Array[Byte] ⇒ AnyRef](HandleCommandManifest -> handleCommandFromBinary)

  override def manifest(o: AnyRef): String = o match {
    case HandleCommand(_) => HandleCommandManifest
    case x                => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case x @ HandleCommand(_) =>
      handleCommandToProto(x.asInstanceOf[HandleCommand[AnyRef]]).toByteArray
    case x => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) => f(bytes)
      case other   => throw new IllegalArgumentException(s"Unknown manifest [$other]")
    }

  private def handleCommandToProto(handleCommand: HandleCommand[AnyRef]): msg.HandleCommand = {
    val command: AnyRef = handleCommand.command
    val msgSerializer = serialization.findSerializerFor(command)
    val manifest = msgSerializer match {
      case ser2: SerializerWithStringManifest ⇒
        val manifest = ser2.manifest(command)
        if (manifest != "")
          ByteString.copyFromUtf8(manifest)
        else
          ByteString.EMPTY
      case _ ⇒
        if (msgSerializer.includeManifest)
          ByteString.copyFromUtf8(command.getClass.getName)
        else
          ByteString.EMPTY
    }
    msg.HandleCommand(
      ByteString.copyFrom(msgSerializer.toBinary(handleCommand.command)),
      msgSerializer.identifier,
      manifest
    )

  }

  private def handleCommandFromBinary(bytes: Array[Byte]): HandleCommand[AnyRef] =
    handleCommandFromProto(msg.HandleCommand.parseFrom(bytes))

  private def handleCommandFromProto(commandEnvelope: msg.HandleCommand): HandleCommand[AnyRef] = {
    val manifest = commandEnvelope.messageManifest.toStringUtf8
    val payload = serialization
      .deserialize(commandEnvelope.command.toByteArray, commandEnvelope.serializerId, manifest)
      .get
    HandleCommand(payload)
  }
}
