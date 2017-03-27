package aecor.aggregate.runtime

import java.util.UUID

import aecor.aggregate.{ Correlation, CorrelationId }
import aecor.aggregate.runtime.behavior.{ Behavior, PairT }
import aecor.data.Handler
import cats.arrow.FunctionK
import cats.implicits._
import cats.{ Monad, ~> }

object VanillaBehavior {

  trait EntityRepository[F[_], S, D] {
    def loadState: F[S]
    def applyChanges(instanceId: UUID, state: S, changes: D): F[S]
  }

  def shared[F[_]: Monad, Op[_], S, D](opHandler: Op ~> Handler[F, S, D, ?],
                                       repository: EntityRepository[F, S, D],
                                       generateInstanceId: F[UUID]): Behavior[Op, F] = {
    def mkBehavior(instanceId: UUID, stateZero: S): Behavior[Op, F] = {
      def rec(state: S): Behavior[Op, F] =
        Behavior(Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { op =>
          opHandler(op).run(state).flatMap {
            case (stateChanges, reply) =>
              repository.applyChanges(instanceId, state, stateChanges).map { nextState =>
                (rec(nextState), reply)
              }
          }
        })
      rec(stateZero)
    }
    Behavior(Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { firstOp =>
      for {
        instanceId <- generateInstanceId
        state <- repository.loadState
        behavior = mkBehavior(instanceId, state)
        result <- behavior.run(firstOp)
      } yield result
    })
  }

  def correlated[F[_]: Monad, Op[_], S, D](
    entityBehavior: Op[_] => Behavior[Op, F]
  ): Behavior[Op, F] =
    Behavior(Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { firstOp =>
      entityBehavior(firstOp).run(firstOp)
    })
}
