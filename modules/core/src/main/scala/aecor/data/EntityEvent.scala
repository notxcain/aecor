package aecor.data

import aecor.Has

final case class EntityEvent[I, A](entityId: I, sequenceNr: Long, payload: A) {
  def map[B](f: A => B): EntityEvent[I, B] = copy(payload = f(payload))
}

object EntityEvent {
  implicit def aecorHasInstanceForId[X, I, A](implicit I: Has[I, X]): Has[EntityEvent[I, A], X] =
    Has.instance[EntityEvent[I, A]](x => I.get(x.entityId))
  implicit def aecorHasInstanceForValue[X, I, A](implicit A: Has[A, X]): Has[EntityEvent[I, A], X] =
    Has.instance[EntityEvent[I, A]](x => A.get(x.payload))
}

