package aecor.example.account

import aecor.encoding.WireProtocol.{Decoder, Encoder}
import aecor.example.account.Rejection.{AccountDoesNotExist, AccountExists, HoldNotFound, InsufficientFunds}


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

  implicit val rejectionEncoder: Encoder[Rejection] =
    Encoder.instance[Rejection](Pickle.intoBytes)

  implicit val rejectionDecoder: Decoder[Rejection] =
    Decoder.fromTry(Unpickle[Rejection].tryFromBytes)

}
