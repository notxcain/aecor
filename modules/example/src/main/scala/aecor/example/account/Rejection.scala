package aecor.example.account

import aecor.macros.boopickle.BoopickleCodec._
import scodec.Codec

sealed abstract class Rejection extends Product with Serializable

object Rejection extends RejectionInstances {
  case object AccountDoesNotExist extends Rejection
  case object InsufficientFunds extends Rejection
  case object HoldNotFound extends Rejection
}

trait RejectionInstances { self: Rejection.type =>
  import boopickle.Default._
  implicit val rejectionPickler = compositePickler[Rejection]
    .addConcreteType[AccountDoesNotExist.type]
    .addConcreteType[InsufficientFunds.type]
    .addConcreteType[HoldNotFound.type]

  implicit val rejectionCodec: Codec[Rejection] =
    codec[Rejection]
}
