package aecor.example.domain

import ESMap.ESMapEvent
import ESMap.ESMapEvent.{ ValueRemoved, ValueSet }
import aecor.data.Folded
import aecor.data.Folded.syntax._

trait Fold[A, B] {
  def zero(a: A): Folded[B]
  def next(b: B, a: A): Folded[B]
}

object Fold {
  def instance[A, B](zero: A => Folded[B])(next: B => A => Folded[B]): Fold[A, B] = {
    val zero0 = zero
    val next0 = next
    new Fold[A, B] {
      override def zero(a: A): Folded[B] = zero0(a)
      override def next(b: B, a: A): Folded[B] = next0(b)(a)
    }
  }
  def withZero[A, B](zero: B)(next: B => A => Folded[B]): Fold[A, B] =
    instance[A, B](a => next(zero)(a))(next)
}

final case class ESMap(map: Map[String, String]) {
  def applyEvent(e: ESMapEvent): Folded[ESMap] = e match {
    case ValueSet(key, value) => ESMap(map.updated(key, value)).next
    case ValueRemoved(key)    => ESMap(map - key).next
  }
}

object ESMap {
  def zero: ESMap = ESMap(Map.empty)
  def fromEvent(e: ESMapEvent): Folded[ESMap] = e match {
    case ValueSet(key, value) => ESMap(Map(key -> value)).next
    case ValueRemoved(_)      => impossible
  }

  sealed abstract class ESMapEvent
  object ESMapEvent {
    final case class ValueSet(key: String, value: String) extends ESMapEvent
    final case class ValueRemoved(key: String) extends ESMapEvent
  }
  implicit val fold: Fold[ESMapEvent, ESMap] =
    Fold.withZero(zero)(_.applyEvent)
}
