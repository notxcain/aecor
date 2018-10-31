package aecor.runtime
import aecor.data.ActionT
import cats.arrow.FunctionK

package object eventsourced {
  type ActionRunner[F[_], S, E] = FunctionK[ActionT[F, S, E, ?], F]
}
