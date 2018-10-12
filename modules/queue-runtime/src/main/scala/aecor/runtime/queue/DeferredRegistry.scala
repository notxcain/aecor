package aecor.runtime.queue

trait DeferredRegistry[F[_], K, A] {
  def defer(key: K): F[F[A]]
  def fulfill(key: K, value: A): F[Unit]
}