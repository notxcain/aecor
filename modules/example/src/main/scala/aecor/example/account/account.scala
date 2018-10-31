package aecor.example
import aecor.runtime.Eventsourced.Entities

package object account {
  type Accounts[F[_]] = Entities.Rejectable[AccountId, Algebra, F, Rejection]
}
