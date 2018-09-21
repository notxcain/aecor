package aecor.example
import aecor.runtime.Eventsourced.Entity

package object account {
  type Accounts[F[_]] = Entity[AccountId, Algebra, F, Rejection]
}
