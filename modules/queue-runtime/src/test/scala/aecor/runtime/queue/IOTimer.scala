package aecor.runtime.queue
import cats.effect.{IO, Timer}

trait IOTimer {
  implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
}
