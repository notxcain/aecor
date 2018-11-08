//package aecor.example2.domain
//
//import aecor.macros.boopickleWireProtocol
//import boopickle.Default._
//
//final case class SubscriptionId(value: java.util.UUID) extends AnyVal
//
//@boopickleWireProtocol
//trait Subscription[F[_]] {
//  def createSubscription(userId: String, productId: String, planId: String): F[Unit]
//  def pauseSubscription: F[Unit]
//  def resumeSubscription: F[Unit]
//  def cancelSubscription: F[Unit]
//}