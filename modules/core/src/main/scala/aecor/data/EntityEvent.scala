package aecor.data

final case class EntityEvent[I, A](entityId: I, sequenceNr: Long, payload: A) {
  def map[B](f: A => B): EntityEvent[I, B] = copy(payload = f(payload))
}
