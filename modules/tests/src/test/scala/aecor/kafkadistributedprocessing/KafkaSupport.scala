package aecor.kafkadistributedprocessing

import net.manub.embeddedkafka.{ EmbeddedKafka, EmbeddedKafkaConfig }
import org.scalatest.{ BeforeAndAfterAll, Suite }

trait KafkaSupport extends EmbeddedKafka with BeforeAndAfterAll { this: Suite =>
  implicit val kafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafka.start()(EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)).config

  def brokers: Set[String] = Set(s"localhost:${kafkaConfig.kafkaPort}")

  override protected def afterAll(): Unit = {
    EmbeddedKafka.stop()
    super.afterAll()
  }
}
