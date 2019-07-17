package aecor.kafkadistributedprocessing

import cats.effect.{ ContextShift, IO, Timer }

trait IOSupport {
  implicit val contextShift: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
}
