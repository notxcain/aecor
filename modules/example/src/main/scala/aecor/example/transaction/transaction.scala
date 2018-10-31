package aecor.example.transaction
import aecor.runtime.Eventsourced.Entities

package object transaction {
  type Transactions[F[_]] = Entities.Rejectable[TransactionId, Algebra, F, String]
}
