package aecor.runtime
import aecor.data.ActionT
import cats.~>

package object eventsourced {
  type ActionRunner[F[_], S, E] = ActionT[F, S, E, ?] ~> F
}
