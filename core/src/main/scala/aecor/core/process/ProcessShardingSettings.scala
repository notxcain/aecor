package aecor.core.process

import aecor.core.actor.SnapshotPolicy
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

  val defaultSnapshotPolicy: SnapshotPolicy = snapshotPolicyAtPath("default-snapshot-after")
  def snapshotPolicy(entityName: String): SnapshotPolicy = {
    val key = s"idle-timeout.$entityName"
    if (config.hasPath(key)) snapshotPolicyAtPath(key)
    else defaultSnapshotPolicy
  }

  private def snapshotPolicyAtPath(path: String): SnapshotPolicy =
    config.getString(path) match {
      case "off" ⇒ SnapshotPolicy.Never
      case _     ⇒ SnapshotPolicy.After(config.getInt(path))
    }

  val parallelism: Int = config.getInt("parallelism")
}
