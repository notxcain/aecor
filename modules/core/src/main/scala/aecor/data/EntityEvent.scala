package aecor.data

import aecor.Has

final case class EntityEvent[K, A](entityKey: K, sequenceNr: Long, payload: A) {
  def map[B](f: A => B): EntityEvent[K, B] = copy(payload = f(payload))
}

object EntityEvent {
  implicit def aecorHasInstanceForKey[X, K, A](implicit K: Has[K, X]): Has[EntityEvent[K, A], X] =
    Has.instance[EntityEvent[K, A]](x => K.get(x.entityKey))
  implicit def aecorHasInstanceForValue[X, K, A](implicit A: Has[A, X]): Has[EntityEvent[K, A], X] =
    Has.instance[EntityEvent[K, A]](x => A.get(x.payload))
}
