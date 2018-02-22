package aecor

import aecor.arrow.Invocation
import cats.~>
import io.aecor.liberator.FunctorK

trait ReifiedInvocations[M[_[_]]] {
  def instance: M[Invocation[M, ?]]
  def create[F[_]](f: Invocation[M, ?] ~> F)(implicit M: FunctorK[M]): M[F] = M.mapK(instance, f)
}

object ReifiedInvocations {
  def apply[M[_[_]]](implicit instance: ReifiedInvocations[M]): ReifiedInvocations[M] = instance
}
