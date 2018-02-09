package aecor.data

trait Ref[K, A] {
  def ref(k: K): A
}
