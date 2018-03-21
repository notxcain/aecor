package aecor.encoding

import boopickle._
import shapeless.{ Cached, Lazy, Unwrapped }

sealed abstract class AnyValPickler[A <: AnyVal] extends Pickler[A]

object AnyValPickler {
  implicit def anyValPickler[A <: AnyVal, U](implicit A: Unwrapped.Aux[A, U],
                                             U: Lazy[boopickle.Pickler[U]]): AnyValPickler[A] =
    new AnyValPickler[A] {
      override def unpickle(implicit state: UnpickleState): A =
        A.wrap(U.value.unpickle(state))
      override def pickle(obj: A)(implicit state: PickleState): Unit =
        U.value.pickle(A.unwrap(obj))
    }
}

trait AnyValPicklerFallback {
  implicit def anyValPickler[A <: AnyVal](
    implicit A: Cached[AnyValPickler[A]]
  ): boopickle.Pickler[A] =
    A.value
}

object AecorBoopickleDefault
    extends Base
    with BasicImplicitPicklers
    with TransformPicklers
    with TuplePicklers
    with AnyValPicklerFallback
    with MaterializePicklerFallback
