package aecor.data

trait KeyValueStore[F[_], K, A] {
  def setValue(key: K, value: A): F[Unit]
  def getValue(key: K): F[Option[A]]
}
