//package aecor.example2.domain
//
//import aecor.data.Folded.syntax._
//import aecor.data._
//import aecor.example2.domain.Subscription
//import aecor.example2.domain.SubscriptionEvent._
//import cats.implicits._
//
//import scala.collection.immutable._
//
//class EventsourcedSubscription {
//
//import aecor.data.Folded.syntax._
//import SubscriptionState._
//
//final case class SubscriptionState(status: Status) {
//  def applyEvent(e: SubscriptionEvent): Folded[Subscription] = e match {
//    case SubscriptionCreated(_, _, _) =>
//      impossible
//    case SubscriptionPaused =>
//      subscription.copy(status = Status.Paused).next
//    case SubscriptionResumed =>
//      copy(status = Status.Active).next
//    case SubscriptionCancelled =>
//      copy(status = Status.Cancelled).next
//  }
//}
//
//object SubscriptionState {
//  sealed abstract class Status extends Product with Serializable
//  object Status {
//    final case object Active extends Status
//    final case object Paused extends Status
//    final case object Cancelled extends Status
//  }
//  def init(e: SubscriptionEvent): Folded[SubscriptionState] = e match {
//    case SubscriptionCreated(userId, productId, planId) =>
//      Subscription(Active).next
//    case _ => impossible
//  }
//}
//}
