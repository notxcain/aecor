package aecor.runtime.akkageneric.tests

import aecor.runtime.akkageneric.GenericAkkaRuntime.KeyedCommand
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.{ Command, CommandResult }
import aecor.runtime.akkageneric.serialization.MessageSerializer
import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import org.scalatest.{ BeforeAndAfterAll, FunSuite }

import scala.concurrent.Await
import scala.concurrent.duration._

class MessageSerializerTest extends FunSuite with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystem("test")

  test("Should use correct serializer") {
    val serialization = SerializationExtension(system)
    assert(serialization.serializerFor(classOf[Command]).isInstanceOf[MessageSerializer])
    assert(serialization.serializerFor(classOf[CommandResult]).isInstanceOf[MessageSerializer])
    assert(serialization.serializerFor(classOf[KeyedCommand]).isInstanceOf[MessageSerializer])
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    ()
  }
}
