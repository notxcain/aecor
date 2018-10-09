package aecor.runtime.queue
import java.util.concurrent.ConcurrentHashMap

import aecor.runtime.KeyValueStore
import cats.effect.Sync

private [queue] final class HashMapKeyValueStore[F[_], K, V] private (store: ConcurrentHashMap[K, V])(implicit F: Sync[F]) extends KeyValueStore[F, K, V] {
  override def setValue(key: K, value: V): F[Unit] = F.delay { store.put(key, value); () }
  override def getValue(key: K): F[Option[V]] = F.delay { Option(store.get(key)) }
  override def deleteValue(key: K): F[Unit] = F.delay { store.remove(key); () }
}

object HashMapKeyValueStore {
  def create[F[_]: Sync, K, V]: F[KeyValueStore[F, K, V]] =
    Sync[F].delay {
      new HashMapKeyValueStore(new ConcurrentHashMap[K, V]())
    }
}
