package aecor.example.account

import cats.Functor
import cats.implicits._

final class DefaultAccountService[F[_]: Functor](accounts: Accounts[F]) extends AccountService[F] {
  override def openAccount(
                            accountId: AccountId,
                            checkBalance: Boolean
                          ): F[Either[String, Unit]] =
    accounts(accountId).open(checkBalance).map(_.left.map(_.toString))
}

object DefaultAccountService {
  def apply[F[_]: Functor](accounts: Accounts[F]): AccountService[F] = new DefaultAccountService[F](accounts)
}