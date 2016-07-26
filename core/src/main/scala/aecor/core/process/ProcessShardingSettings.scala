package aecor.core.process

import com.typesafe.config.Config
import aecor.util.ConfigHelpers._
import scala.concurrent.duration.FiniteDuration

class ProcessShardingSettings(config: Config) {
  val eventRedeliveryInterval: FiniteDuration = config.getMillisDuration("event-redelivery-interval")
  val deliveryTimeout: FiniteDuration = config.getMillisDuration("delivery-timeout")
  val numberOfShards: Int = config.getInt("number-of-shards")
  val parallelism: Int = config.getInt("parallelism")
}
