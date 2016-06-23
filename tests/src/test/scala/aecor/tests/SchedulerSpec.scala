package aecor.tests

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}


class SchedulerSpec extends TestKit(ActorSystem("SchedulerSpec")) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A SchedulerActor actor" must {
    "send back messages unchanged" in {
//      val echo = system.actorOf(SchedulerA)
//      echo ! "hello world"
//      expectMsg("hello world")
    }

  }
}
