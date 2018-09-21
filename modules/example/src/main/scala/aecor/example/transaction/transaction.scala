package aecor.example.transaction
import aecor.runtime.Eventsourced.Entity

package object transaction {
  type Transactions[F[_]] = Entity[TransactionId, Algebra, F, String]
}
