package aecor.example

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object App extends App {
  val config = ConfigFactory.load()
  val actorSystem = ActorSystem(config.getString("cluster.system-name"))
  actorSystem.actorOf(AppActor.props, "root")
  actorSystem.registerOnTermination {
    System.exit(1)
  }
}