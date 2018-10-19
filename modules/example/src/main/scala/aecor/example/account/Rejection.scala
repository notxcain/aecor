package aecor.example.account

import aecor.example.account.Rejection.{
  AccountDoesNotExist,
  AccountExists,
  HoldNotFound,
  InsufficientFunds
}
import aecor.macros.boopickle.BoopickleCodec._
import scodec.Codec

sealed abstract class Rejection extends Product with Serializable

object Rejection extends RejectionInstances {
  case object AccountDoesNotExist extends Rejection
  case object InsufficientFunds extends Rejection
  case object AccountExists extends Rejection
  case object HoldNotFound extends Rejection
}

trait RejectionInstances {
  import boopickle.Default._
  implicit val rejectionPickler = compositePickler[Rejection]
    .addConcreteType[AccountDoesNotExist.type]
    .addConcreteType[InsufficientFunds.type]
    .addConcreteType[AccountExists.type]
    .addConcreteType[HoldNotFound.type]

  implicit val rejectionCodec: Codec[Rejection] =
    codec[Rejection]
}
