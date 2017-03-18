package aecor.aggregate

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterShardingSettings

import scala.concurrent.duration._

final case class AkkaRuntimeSettings(numberOfShards: Int,
                                     idleTimeout: FiniteDuration,
                                     askTimeout: FiniteDuration,
                                     clusterShardingSettings: ClusterShardingSettings)

object AkkaRuntimeSettings {

  /**
    * Reads config from `aecor.akka-runtime`, see reference.conf for details
    * @param system Actor system to get config from
    * @return default settings
    */
  def default(system: ActorSystem): AkkaRuntimeSettings = {
    val config = system.settings.config.getConfig("aecor.akka-runtime")
    def getMillisDuration(path: String): FiniteDuration =
      Duration(config.getDuration(path, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

    AkkaRuntimeSettings(
      config.getInt("number-of-shards"),
      getMillisDuration("idle-timeout"),
      getMillisDuration("ask-timeout"),
      ClusterShardingSettings(system)
    )
  }
}
