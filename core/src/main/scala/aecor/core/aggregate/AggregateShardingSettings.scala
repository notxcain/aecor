package aecor.core.aggregate

import aecor.util.ConfigHelpers._
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

class AggregateShardingSettings(config: Config) {
  val numberOfShards: Int = config.getInt("number-of-shards")
  val askTimeout: FiniteDuration = config.getMillisDuration("ask-timeout")
  val defaultIdleTimeout: FiniteDuration = config.getMillisDuration("default-idle-timeout")
  def idleTimeout(entityName: String): FiniteDuration = {
    val key = s"idle-timeout.$entityName"
    if (config.hasPath(key)) config.getMillisDuration(key) else defaultIdleTimeout
  }
}
