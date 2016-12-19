package aecor.core.aggregate

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterShardingSettings
import com.typesafe.config.Config

import scala.concurrent.duration._

class AggregateShardingSettings(
    config: Config,
    val clusterShardingSettings: ClusterShardingSettings) {

  private def getMillisDuration(config: Config, path: String): FiniteDuration =
    Duration(config.getDuration(path, TimeUnit.MILLISECONDS),
             TimeUnit.MILLISECONDS)

  val numberOfShards: Int = config.getInt("number-of-shards")
  val defaultIdleTimeout: FiniteDuration =
    getMillisDuration(config, "default-idle-timeout")

  def idleTimeout(name: String): FiniteDuration = {
    val key = s"idle-timeout.$name"
    if (config.hasPath(key)) getMillisDuration(config, key)
    else defaultIdleTimeout
  }

  val defaultSnapshotPolicy: SnapshotPolicy = snapshotPolicyAtPath(
    "default-snapshot-after")
  def snapshotPolicy(entityName: String): SnapshotPolicy = {
    val key = s"idle-timeout.$entityName"
    if (config.hasPath(key)) snapshotPolicyAtPath(key)
    else defaultSnapshotPolicy
  }

  private def snapshotPolicyAtPath(path: String): SnapshotPolicy =
    config.getString(path) match {
      case "off" ⇒ SnapshotPolicy.Never
      case _ ⇒ SnapshotPolicy.EachNumberOfEvents(config.getInt(path))
    }

  val askTimeout: FiniteDuration = getMillisDuration(config, "ask-timeout")
}

object AggregateShardingSettings {
  def apply(system: ActorSystem): AggregateShardingSettings =
    new AggregateShardingSettings(
      system.settings.config.getConfig("aecor.aggregate"),
      ClusterShardingSettings(system))
}
