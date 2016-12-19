package aecor.serialization.kafka

import java.util

import org.apache.kafka.common.serialization.Serializer

trait PureSerializer[A] extends Serializer[A] {
  final override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
  final override def close(): Unit = ()
}
