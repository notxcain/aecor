package aecor.data

final case class Identified[I, A](id: I, a: A) {
  def map[B](f: A => B): Identified[I, B] = Identified(id, f(a))
}
