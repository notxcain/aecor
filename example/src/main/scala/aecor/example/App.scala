package aecor.example

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object App {
  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val actorSystem = ActorSystem(config.getString("cluster.system-name"))
    actorSystem.actorOf(AppActor.props, "root")
    actorSystem.registerOnTermination {
      System.exit(1)
    }
  }

}
