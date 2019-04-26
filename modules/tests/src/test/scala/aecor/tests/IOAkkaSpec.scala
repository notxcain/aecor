package aecor.tests

import cats.effect.{ ContextShift, IO, Timer }

trait IOAkkaSpec extends AkkaSpec {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(system.dispatcher)
  implicit val timer: Timer[IO] = IO.timer(system.dispatcher)
}
