package aecor.example.domain
import java.util.UUID

import aecor.aggregate.Correlation
import aecor.data.Folded.syntax._
import aecor.data.{ Behavior, Folded, Handler }
import aecor.example.domain.CardAuthorizationAggregate.State.{
  Accepted,
  Created,
  Declined,
  Initial
}
import aecor.example.domain.CardAuthorizationAggregateEvent.{
  CardAuthorizationAccepted,
  CardAuthorizationCreated,
  CardAuthorizationDeclined
}
import aecor.example.domain.CardAuthorizationAggregateOp._
import akka.Done
import cats.~>

import scala.collection.immutable.Seq

case class TransactionId(value: String) extends AnyVal

object CardAuthorizationAggregate {
  sealed trait State {
    def applyEvent(event: CardAuthorizationAggregateEvent): Folded[State] =
      this match {
        case Initial =>
          event match {
            case e: CardAuthorizationCreated =>
              Created(e.cardAuthorizationId).next
            case _ =>
              impossible
          }
        case self: Created =>
          event match {
            case e: CardAuthorizationCreated => impossible
            case e: CardAuthorizationAccepted => Accepted(self.id).next
            case e: CardAuthorizationDeclined => Declined(self.id).next
          }
        case self: Accepted =>
          event match {
            case e: CardAuthorizationCreated => impossible
            case e: CardAuthorizationAccepted => Accepted(self.id).next
            case e: CardAuthorizationDeclined => Declined(self.id).next
          }
        case self: Declined =>
          event match {
            case e: CardAuthorizationCreated => impossible
            case e: CardAuthorizationAccepted => Accepted(self.id).next
            case e: CardAuthorizationDeclined => Declined(self.id).next
          }
      }
  }
  object State {
    case object Initial extends State
    case class Created(id: CardAuthorizationId) extends State
    case class Accepted(id: CardAuthorizationId) extends State
    case class Declined(id: CardAuthorizationId) extends State
  }

  def correlation: Correlation[CardAuthorizationAggregateOp] =
    new Correlation[CardAuthorizationAggregateOp] {
      def apply[A](fa: CardAuthorizationAggregateOp[A]) =
        fa.cardAuthorizationId.value
    }

  val entityName: String = "CardAuthorization"

  def behavior: Behavior[CardAuthorizationAggregateOp, State, CardAuthorizationAggregateEvent] =
    Behavior(
      commandHandler =
        new (CardAuthorizationAggregateOp ~> Handler[State, CardAuthorizationAggregateEvent, ?]) {
          def accept[R, E](events: E*): (Seq[E], Either[R, Done]) =
            (events.toVector, Right(Done))

          def reject[R, E](rejection: R): (Seq[E], Either[R, Done]) =
            (Seq.empty, Left(rejection))
          override def apply[A](command: CardAuthorizationAggregateOp[A]) =
            Handler {
              case Initial =>
                command match {
                  case CreateCardAuthorization(
                      cardAuthorizationId,
                      accountId,
                      amount,
                      acquireId,
                      terminalId
                      ) =>
                    accept(
                      CardAuthorizationCreated(
                        cardAuthorizationId,
                        accountId,
                        amount,
                        acquireId,
                        terminalId,
                        TransactionId(UUID.randomUUID().toString)
                      )
                    )
                  case c: AcceptCardAuthorization =>
                    reject(DoesNotExists)
                  case c: DeclineCardAuthorization =>
                    reject(DoesNotExists)
                }
              case Created(id) =>
                command match {
                  case e: AcceptCardAuthorization =>
                    accept(CardAuthorizationAccepted(id))
                  case e: DeclineCardAuthorization =>
                    accept(CardAuthorizationDeclined(id, e.reason))
                  case e: CreateCardAuthorization =>
                    reject(AlreadyExists)
                }
              case Accepted(id) =>
                command match {
                  case e: AcceptCardAuthorization => reject(AlreadyAccepted)
                  case e: DeclineCardAuthorization => reject(AlreadyAccepted)
                  case e: CreateCardAuthorization => reject(AlreadyExists)
                }
              case Declined(id) =>
                command match {
                  case e: AcceptCardAuthorization => reject(AlreadyDeclined)
                  case e: DeclineCardAuthorization => reject(AlreadyDeclined)
                  case e: CreateCardAuthorization => reject(AlreadyExists)
                }
            }
        },
      init = Initial,
      update = _.applyEvent(_)
    )

}
case class CardAuthorizationId(value: String) extends AnyVal
case class CardNumber(value: String) extends AnyVal
case class AcquireId(value: Long) extends AnyVal
case class TerminalId(value: Long) extends AnyVal
