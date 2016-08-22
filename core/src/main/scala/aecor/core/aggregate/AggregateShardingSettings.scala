package aecor.core.aggregate

import aecor.core.actor.SnapshotPolicy
import aecor.util.ConfigHelpers._
import com.typesafe.config.Config

import scala.concurrent.duration._

class AggregateShardingSettings(config: Config) {
  val numberOfShards: Int = config.getInt("number-of-shards")
  val askTimeout: FiniteDuration = config.getMillisDuration("ask-timeout")
  val defaultIdleTimeout: FiniteDuration = config.getMillisDuration("default-idle-timeout")

  def idleTimeout(entityName: String): FiniteDuration = {
    val key = s"idle-timeout.$entityName"
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
}
