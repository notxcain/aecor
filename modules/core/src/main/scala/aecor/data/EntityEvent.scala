package aecor.data

import aecor.Has

final case class EntityEvent[K, A](entityKey: K, sequenceNr: Long, payload: A) {
  def map[B](f: A => B): EntityEvent[K, B] = copy(payload = f(payload))
}

object EntityEvent {
  implicit def aecorHasInstanceForKey[X, K, A](implicit I: Has[K, X]): Has[EntityEvent[K, A], X] =
    Has.instance[EntityEvent[K, A]](x => I.get(x.entityKey))
  implicit def aecorHasInstanceForValue[X, I, A](implicit A: Has[A, X]): Has[EntityEvent[I, A], X] =
    Has.instance[EntityEvent[I, A]](x => A.get(x.payload))
}

