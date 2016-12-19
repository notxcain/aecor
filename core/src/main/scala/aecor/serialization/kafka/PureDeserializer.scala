package aecor.serialization.kafka

import java.util

import org.apache.kafka.common.serialization.Deserializer

trait PureDeserializer[A] extends Deserializer[A] {
  final override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = ()
  final override def close(): Unit = ()
}
