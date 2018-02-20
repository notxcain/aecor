package aecor

import aecor.arrow.Invocation
import cats.~>
import io.aecor.liberator.FunctorK

trait ReifiedInvocation[M[_[_]]] extends FunctorK[M] {
  def instance: M[Invocation[M, ?]]
  def create[F[_]](f: Invocation[M, ?] ~> F): M[F] = mapK(instance, f)
}
