package aecor.runtime.akkageneric

import aecor.runtime.akkageneric.GenericAkkaRuntime.KeyedCommand
import aecor.runtime.akkageneric.GenericAkkaRuntimeActor.{ Command, CommandResult }
import aecor.runtime.akkageneric.serialization.MessageSerializer
import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop.forAll
import org.scalatest.prop.Checkers
import org.scalatest.{ BeforeAndAfterAll, FunSuite }
import scodec.bits.BitVector

import scala.concurrent.Await
import scala.concurrent.duration._

class MessageSerializerTest extends FunSuite with BeforeAndAfterAll with Checkers {

  implicit val system: ActorSystem = ActorSystem("test")
  val serialization = SerializationExtension(system)
  implicit val bitVector: Arbitrary[BitVector] = Arbitrary(arbitrary[Array[Byte]].map(BitVector(_)))

  def canSerialize[A <: AnyRef](a: A): Boolean = {
    val ser = serialization.serializerFor(a.getClass)
    assert(ser.isInstanceOf[MessageSerializer])
    val mser = ser.asInstanceOf[MessageSerializer]
    val (man, bytes) = (mser.manifest(a), mser.toBinary(a))
    val out = mser.fromBinary(bytes, man)
    out === a
  }

  test("serialization") {
    forAll { bb: BitVector =>
      canSerialize(Command(bb))
    }
    forAll { bb: BitVector =>
      canSerialize(CommandResult(bb))
    }
    forAll { (key: String, bb: BitVector) =>
      canSerialize(KeyedCommand(key, bb))
    }
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    ()
  }
}
