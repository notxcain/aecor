package aecor.core.aggregate

import aecor.util.ConfigHelpers._
import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterShardingSettings
import com.typesafe.config.Config

import scala.concurrent.duration._

class AggregateShardingSettings(config: Config, val clusterShardingSettings: ClusterShardingSettings) {

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

  val askTimeout: FiniteDuration = config.getMillisDuration("ask-timeout")
}

object AggregateShardingSettings {
  def apply(system: ActorSystem): AggregateShardingSettings =
    new AggregateShardingSettings(system.settings.config.getConfig("aecor.aggregate"), ClusterShardingSettings(system))
}
