package aecor.example.domain.account

import aecor.effect.Async
import aecor.example.domain.Amount
import aecor.example.domain.account.AccountAggregate.AccountExists
import aecor.example.domain.account.AccountEvent.AccountOpened
import aecor.example.domain.account.EventsourcedAccountAggregate.Account
import aecor.example.domain.account.WithOps2n.{ HandlerOp, HandlerOps }
import aecor.util.Clock
import cats.data.{ OptionT, StateT }
import cats.free.FreeT
import cats.implicits._
import cats.{ Applicative, Monad, ~> }

import scala.collection.immutable._

case class WithOps2n[F[_], E, S, A](f: HandlerOps[F, S, E, A] => FreeT[HandlerOp[S, E, ?], F, A])

object WithOps2n {
  def lift[F[_], S, E, A](
    f: S => F[(Seq[E], A)]
  )(implicit F: Applicative[F]): WithOps2n[F, E, S, A] = WithOps2n { ops =>
    import ops._
    for {
      s <- inspect
      result <- ops.lift(f(s))
      (events, reply) = result
      _ <- emit(events: _*)
    } yield reply
  }
  trait HandlerOps[F[_], S, E, A] {
    def inspect: FreeT[HandlerOp[S, E, ?], F, S]
    def lift[X](fa: F[X]): FreeT[HandlerOp[S, E, ?], F, X]
    def emit(events: E*): FreeT[HandlerOp[S, E, ?], F, Unit]
    def abort: FreeT[HandlerOp[S, E, ?], F, Unit]
    def replyF(a: A): FreeT[HandlerOp[S, E, ?], F, A]
    def reply(a: A): A
  }

  sealed abstract class HandlerOp[S, E, A]
  case class Emit[S, E, A](events: Vector[E], f: Unit => A) extends HandlerOp[S, E, A]
  case class Abort[S, E, A](f: Unit => A) extends HandlerOp[S, E, A]
  case class Inspect[S, E, A](f: S => A) extends HandlerOp[S, E, A]

  def handlerOps[F[_], S, E, A](implicit F: Applicative[F]): HandlerOps[F, S, E, A] =
    new HandlerOps[F, S, E, A] {

      override def inspect: FreeT[HandlerOp[S, E, ?], F, S] =
        FreeT.liftF[HandlerOp[S, E, ?], F, S](Inspect[S, E, S](identity))

      override def lift[X](fa: F[X]): FreeT[HandlerOp[S, E, ?], F, X] = FreeT.liftT(fa)

      override def emit(events: E*): FreeT[HandlerOp[S, E, ?], F, Unit] =
        FreeT.liftF[HandlerOp[S, E, ?], F, Unit](Emit[S, E, Unit](events.toVector, identity))

      override def replyF(a: A): FreeT[HandlerOp[S, E, ?], F, A] = FreeT.pure(a)

      override def abort: FreeT[HandlerOp[S, E, ?], F, Unit] =
        FreeT.liftF[HandlerOp[S, E, ?], F, Unit](Abort[S, E, Unit](identity))

      override def reply(a: A): A = a
    }

  case class OpState[S, E](initial: S, current: S, uncommitted: Vector[E]) {
    def applyEvents(reducer: (S, E) => Option[S])(es: Vector[E]): Option[OpState[S, E]] =
      es.foldM(current)(reducer).map(s => copy(current = s, uncommitted = uncommitted ++ es))
    def reset: OpState[S, E] = copy(initial, initial, Vector.empty)
  }

  def run[F[_]: Async: Monad, S, E, A](action: FreeT[HandlerOp[S, E, ?], F, A],
                                       reducer: (S, E) => Option[S]) =
    action
      .hoist(new (F ~> StateT[OptionT[F, ?], OpState[S, E], ?]) {
        override def apply[X](fa: F[X]): StateT[OptionT[F, ?], OpState[S, E], X] =
          StateT.lift(OptionT.liftF(fa))
      })
      .foldMap(new (HandlerOp[S, E, ?] ~> StateT[OptionT[F, ?], OpState[S, E], ?]) {
        override def apply[X](fa: HandlerOp[S, E, X]): StateT[OptionT[F, ?], OpState[S, E], X] =
          fa match {
            case Emit(events, f) =>
              StateT
                .modifyF[OptionT[F, ?], OpState[S, E]](
                  x => OptionT.fromOption[F](x.applyEvents(reducer)(events))
                )
                .map(f)
            case Inspect(f) =>
              StateT.get[OptionT[F, ?], OpState[S, E]].map(_.current).map(f)
            case Abort(f) =>
              StateT.modify[OptionT[F, ?], OpState[S, E]](_.reset).map(f)
          }
      })
}

class EAA2[F[_]](clock: Clock[F])(implicit F: Applicative[F])
    extends AccountAggregate[WithOps2n[F, AccountEvent, Option[Account], ?]] {

  override def openAccount(
    accountId: AccountId
  ): WithOps2n[F, AccountEvent, Option[Account], Either[AccountAggregate.Rejection, Unit]] =
    WithOps2n { ops =>
      import ops._
      for {
        ts <- lift(clock.instant)
        out <- inspect.flatMap {
                case None =>
                  emit(AccountOpened(accountId, ts.getEpochSecond)).map(_ => reply(().asRight))
                case Some(x) =>
                  replyF(AccountExists.asLeft)
              }
      } yield out
    }

  def accept[R](events: AccountEvent*): (Seq[AccountEvent], Either[R, Unit]) =
    (events.toVector, ().asRight)

  def reject[R](r: R): (Seq[AccountEvent], Either[R, Unit]) = (Seq.empty, r.asLeft)

  def openAccount2(
    accountId: AccountId
  ): WithOps2n[F, AccountEvent, Option[Account], Either[AccountAggregate.Rejection, Unit]] =
    WithOps2n.lift {
      case None =>
        clock.instant.map { ts =>
          accept(AccountOpened(accountId, ts.getEpochSecond))
        }
      case Some(x) =>
        F.pure(reject(AccountExists))
        F.pure((Seq.empty, AccountExists.asLeft))
    }
  override def creditAccount(
    accountId: AccountId,
    transactionId: AccountTransactionId,
    amount: Amount
  ): WithOps2n[F, AccountEvent, Option[Account], Either[AccountAggregate.Rejection, Unit]] = ???

  override def debitAccount(
    accountId: AccountId,
    transactionId: AccountTransactionId,
    amount: Amount
  ): WithOps2n[F, AccountEvent, Option[Account], Either[AccountAggregate.Rejection, Unit]] = ???
}
