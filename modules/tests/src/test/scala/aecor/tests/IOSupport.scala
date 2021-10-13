package aecor.tests

import cats.effect.unsafe.IORuntime

trait IOSupport {
  implicit val ioRuntime: IORuntime = IORuntime.global
}
