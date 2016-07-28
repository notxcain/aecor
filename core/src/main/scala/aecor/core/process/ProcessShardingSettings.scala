package aecor.core.process

import com.typesafe.config.Config
import aecor.util.ConfigHelpers._
import scala.concurrent.duration.FiniteDuration

class ProcessShardingSettings(config: Config) {
  val eventRedeliveryInterval: FiniteDuration = config.getMillisDuration("event-redelivery-interval")
  val deliveryTimeout: FiniteDuration = config.getMillisDuration("delivery-timeout")
  val numberOfShards: Int = config.getInt("number-of-shards")
  val defaultIdleTimeout: FiniteDuration = config.getMillisDuration("default-idle-timeout")
  def idleTimeout(name: String): FiniteDuration = {
    val key = s"idle-timeout.$name"
    if (config.hasPath(key)) config.getMillisDuration(key) else defaultIdleTimeout
  }
  val parallelism: Int = config.getInt("parallelism")
}
