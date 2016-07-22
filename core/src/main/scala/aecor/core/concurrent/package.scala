package aecor.core

import cats.data.Kleisli

import scala.concurrent.{ExecutionContext, Future}


package object concurrent {
  type Deferred[A] = Kleisli[Future, ExecutionContext, A]
  implicit def toDeferredOps[A](a: Deferred[A]): DeferredOps[A] = new DeferredOps(a)
}
