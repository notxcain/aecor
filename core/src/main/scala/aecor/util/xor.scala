package aecor.util

import cats.data.{Xor, XorT}


class FXorOps[F[_], A, B](val self: F[Xor[A, B]]) extends AnyVal {
  def toXorT: XorT[F, A, B] = XorT(self)
}

object xor {
  implicit def toXorOps[F[_], A, B](self: F[Xor[A, B]]): FXorOps[F, A, B] = new FXorOps[F, A, B](self)
}
