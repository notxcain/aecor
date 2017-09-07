package aecor.example.domain.account

import aecor.encoding.{ KeyDecoder, KeyEncoder }

final case class AccountId(value: String) extends AnyVal
object AccountId {
  implicit val keyEncoder: KeyEncoder[AccountId] = KeyEncoder.instance(_.value)
  implicit val keyDecoder: KeyDecoder[AccountId] = KeyDecoder.instance(x => Some(AccountId(x)))
}
