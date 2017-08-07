package aecor.data

trait Reducer[A, B] {
  def unit(a: A): B
  def reduce(b: B, a: A): B
}
