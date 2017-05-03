package aecor.experimental

import aecor.data.{ Behavior, Handler, PairT }
import cats.implicits._
import cats.{ Monad, ~> }

object VanillaBehavior {

  trait EntityRepository[F[_], S, E] {
    def loadState: F[S]
    def applyStateEffect(state: S, stateEffect: E): F[S]
  }

  def shared[F[_]: Monad, Op[_], S, D](opHandler: Op ~> Handler[F, S, D, ?],
                                       repository: EntityRepository[F, S, D]): Behavior[F, Op] = {
    def mkBehavior(stateZero: S): Behavior[F, Op] = {
      def rec(state: S): Behavior[F, Op] =
        Behavior(Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { op =>
          opHandler(op).run(state).flatMap {
            case (stateChanges, reply) =>
              repository.applyStateEffect(state, stateChanges).map { nextState =>
                (rec(nextState), reply)
              }
          }
        })
      rec(stateZero)
    }
    Behavior(Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { firstOp =>
      for {
        state <- repository.loadState
        behavior = mkBehavior(state)
        result <- behavior.run(firstOp)
      } yield result
    })
  }

  def correlated[F[_]: Monad, Op[_], S, D](
    entityBehavior: Op[_] => Behavior[F, Op]
  ): Behavior[F, Op] =
    Behavior(Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { firstOp =>
      entityBehavior(firstOp).run(firstOp)
    })
}
