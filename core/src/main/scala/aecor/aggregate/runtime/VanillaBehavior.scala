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

  def shared[F[_]: Monad, Op[_], S, D](opHandler: Op ~> Handler[S, D, ?],
                                       repository: EntityRepository[F, S, D],
                                       generateInstanceId: F[UUID]): Behavior[Op, F] = {
    def mkBehavior(instanceId: UUID, stateZero: S): Behavior[Op, F] = {
      def rec(state: S): Behavior[Op, F] =
        Behavior(Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { op =>
          val (stateChanges, reply) = opHandler(op).run(state)
          repository.applyChanges(instanceId, state, stateChanges).map { nextState =>
            (rec(nextState), reply)
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
    entityName: String,
    correlation: Correlation[Op],
    entityBehavior: CorrelationId => Behavior[Op, F]
  ): Behavior[Op, F] =
    Behavior(Lambda[Op ~> PairT[F, Behavior[Op, F], ?]] { firstOp =>
      for {
        entityId <- s"$entityName-${correlation(firstOp)}".pure[F]
        behavior = entityBehavior(entityId)
        result <- behavior.run(firstOp)
      } yield result
    })

  def apply[F[_]: Monad, Op[_], S, D](entityName: String,
                                      correlation: Correlation[Op],
                                      opHandler: Op ~> Handler[S, D, ?],
                                      loadState: String => F[S],
                                      updateState: (String, UUID, S, D) => F[S],
                                      generateInstanceId: F[UUID]): Behavior[Op, F] = {
    def mkBehavior(entityId: String, instanceId: UUID, stateZero: S): Behavior[Op, F] = {
      def rec(state: S): Behavior[Op, F] =
        Behavior[Op, F] {
          def mk[A](op: Op[A]): PairT[F, Behavior[Op, F], A] = {
            val (stateChanges, reply) = opHandler(op).run(state)
            updateState(entityId, instanceId, state, stateChanges).map { nextState =>
              (rec(nextState), reply)
            }
          }
          FunctionK.lift(mk _)
        }
      rec(stateZero)
    }
    Behavior[Op, F](new (Op ~> PairT[F, Behavior[Op, F], ?]) {
      override def apply[A](firstOp: Op[A]): PairT[F, Behavior[Op, F], A] =
        for {
          instanceId <- generateInstanceId
          entityId = s"$entityName-${correlation(firstOp)}"
          state <- loadState(entityId)
          behavior = mkBehavior(entityId, instanceId, state)
          result <- behavior.run(firstOp)
        } yield result
    })
  }

}
