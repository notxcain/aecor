package aecor.example.account

import cats.Functor
import cats.implicits._

final class DefaultService[F[_]: Functor](accounts: Accounts[F]) extends Service[F] {
  override def openAccount(
                            accountId: AccountId,
                            checkBalance: Boolean
                          ): F[Either[String, Unit]] =
    accounts(accountId).open(checkBalance).map(_.left.map(_.toString))
}

object DefaultService {
  def apply[F[_]: Functor](accounts: Accounts[F]): Service[F] = new DefaultService[F](accounts)
}