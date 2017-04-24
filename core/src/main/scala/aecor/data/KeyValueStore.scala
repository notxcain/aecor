package aecor.data

trait KeyValueStore[F[_], K, V] {
  def setValue(key: K, value: V): F[Unit]
  def getValue(key: K): F[Option[V]]
}
