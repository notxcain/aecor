package aecor.tests

import cats.effect.{ ContextShift, IO, Timer }
import org.scalatest.Suite

trait IOAkkaSpec extends TestActorSystem { suite: Suite =>
  implicit val contextShift: ContextShift[IO] = IO.contextShift(system.dispatcher)
  implicit val timer: Timer[IO] = IO.timer(system.dispatcher)
}
