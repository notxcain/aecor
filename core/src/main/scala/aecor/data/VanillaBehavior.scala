package aecor.data

import cats.implicits._
import cats.{ Monad, ~> }

object VanillaBehavior {

  trait EntityRepository[F[_], S, D] {
    def loadState: F[S]
    def applyChanges(state: S, changes: D): F[S]
  }

  def shared[F[_]: Monad, Op[_], S, D](opHandler: Op ~> Handler[F, S, D, ?],
                                       repository: EntityRepository[F, S, D]): Behavior[Op, F] = {
    def mkBehavior(stateZero: S): Behavior[Op, F] = {
      def rec(state: S): Behavior[Op, F] =
        Behavior(Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { op =>
          opHandler(op).run(state).flatMap {
            case (stateChanges, reply) =>
              repository.applyChanges(state, stateChanges).map { nextState =>
                (rec(nextState), reply)
              }
          }
        })
      rec(stateZero)
    }
    Behavior(Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { firstOp =>
      for {
        state <- repository.loadState
        behavior = mkBehavior(state)
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
