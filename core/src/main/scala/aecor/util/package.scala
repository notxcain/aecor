package aecor

import java.util.UUID

import shapeless.Unwrapped

package object util {
  def generate[A <: AnyVal](implicit unwrapped: Unwrapped.Aux[A, String]): A = unwrapped.wrap(UUID.randomUUID().toString)
}
