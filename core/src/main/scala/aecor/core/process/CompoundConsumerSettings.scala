package aecor.core.process

import java.nio.ByteBuffer

import aecor.core.entity.EventContract.Aux
import cats.std.function._
import cats.syntax.functor._
import aecor.core.entity.{EntityName, EventContract}
import aecor.core.serialization.Decoder
import shapeless.{:+:, ::, CNil, Coproduct, Generic, HList, HNil}


case class TopicSchema[A](topic: String, read: Array[Byte] => Option[A]) {
  def map[B](f: A => B): TopicSchema[B] = TopicSchema(topic, read.andThen(_.map(f)))
    def collect[B](f: PartialFunction[A, B]) = TopicSchema(topic, read.andThen(_.collect(f)))
}

trait ComposeConfig[Schema] {
  type Out
  def apply(schema: Schema): Map[String, Array[Byte] => Option[Out]]
}

object ComposeConfig extends LowPriorityInstances {
  type Aux[Schema, Out0] = ComposeConfig[Schema] {
    type Out = Out0
  }

  def apply[Schema](s: Schema)(implicit instance: ComposeConfig[Schema]): Map[String, Array[Byte] => Option[instance.Out]] = instance(s)

  implicit def hcons[O, T <: HList, TO <: Coproduct](implicit t: ComposeConfig.Aux[T, TO]): ComposeConfig.Aux[TopicSchema[O] :: T, O :+: TO] = new ComposeConfig[TopicSchema[O] :: T] {
    override def apply(schema: TopicSchema[O] :: T): Map[String, Array[Byte] => Option[Out]] = {
      val TopicSchema(topic, f) = schema.head
      t(schema.tail).mapValues(_.map(_.map(_.extendLeft[O]))) + (topic -> f.map(_.map(Coproduct[Out](_))))
    }

    override type Out = O :+: TO
  }

  implicit def hconsNil[O]: ComposeConfig.Aux[TopicSchema[O] :: HNil, O :+: CNil] = new ComposeConfig[TopicSchema[O] :: HNil] {
    override def apply(schema: TopicSchema[O] :: HNil): Map[String, Array[Byte] => Option[Out]] = {
      val TopicSchema(topic, f) = schema.head
      Map(topic -> f.andThen(_.map(Coproduct[Out](_))))
    }
    override type Out = O :+: CNil
  }
}

trait LowPriorityInstances {
  implicit def genCompose[A, Repr, Out0](implicit gen: Generic.Aux[A, Repr], c: ComposeConfig.Aux[Repr, Out0]): ComposeConfig.Aux[A, Out0] = new ComposeConfig[A] {
    override type Out = Out0
    override def apply(schema: A): Map[String, (Array[Byte]) => Option[Out]] = c(gen.to(schema))
  }
  implicit def singleTopic[A]: ComposeConfig.Aux[TopicSchema[A], A] = new ComposeConfig[TopicSchema[A]] {
    override type Out = A

    override def apply(schema: TopicSchema[A]): Map[String, (Array[Byte]) => Option[this.Out]] = Map(schema.topic -> schema.read)
  }
}


object CompositeConsumerSettingsSyntax {
  trait MkFrom[A] {
    def apply[T]()(implicit arName: EntityName[A], contract: EventContract.Aux[A, T], decoder: Decoder[T]): TopicSchema[T]
  }
  def from[A] = new MkFrom[A] {
    override def apply[T]()(implicit arName: EntityName[A], contract: Aux[A, T], decoder: Decoder[T]): TopicSchema[T] =
      TopicSchema(arName.value, bytes => decoder.decode(ByteBuffer.wrap(bytes)).toOption)
  }

}

