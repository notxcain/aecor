package aecor.runtime.queue
import cats.effect.IO

trait IOContextShift {
  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)
}
