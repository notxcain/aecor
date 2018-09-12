package aecor.example.account
import aecor.example.account.http.Service
import aecor.example.domain.account.{Account, AccountId}
import cats.Monad
import cats.data.EitherT

object service {
  type Accounts[F[_]] = AccountId => Account[EitherT[F, Account.Rejection, ?]]
  def default[F[_]: Monad](accounts: Accounts[F]): Service[F] = new Service[F] {
    override def openAccount(
      accountId: AccountId,
      checkBalance: Boolean
    ): F[Either[String, Unit]] =
      F.ask.flatMap(_(accountId).open(checkBalance).leftMap(_.toString).value)
  }
}
