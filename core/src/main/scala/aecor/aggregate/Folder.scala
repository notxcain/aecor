package aecor.aggregate

trait Folder[A, B] {
  def zero: B
  def fold(b: B, a: A): B
}
