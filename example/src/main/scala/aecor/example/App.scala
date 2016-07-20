package aecor.example

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import kamon.Kamon

object App extends App {
  Kamon.start()
  val config = ConfigFactory.load()
  val actorSystem = ActorSystem(config.getString("cluster.system-name"))
  actorSystem.actorOf(AppActor.props, "root")
  actorSystem.registerOnTermination {
    System.exit(1)
  }
}