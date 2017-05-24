package aecor.experimental

import aecor.data._
import cats.data.StateT
import cats.implicits._
import cats.{ Monad, ~> }

object VanillaBehavior {
  def shared[F[_]: Monad, Op[_], S, D](opHandler: Op ~> Handler[F, S, D, ?],
                                       folder: F[Folder[F, D, S]]): Behavior[F, Op] =
    Behavior.roll {
      folder.map { f =>
        StateBehavior(state = f.zero.pure[F], f = new (Op ~> StateT[F, S, ?]) {
          override def apply[A](op: Op[A]): StateT[F, S, A] =
            StateT { state =>
              for {
                x <- opHandler(op).run(state)
                (stateChanges, reply) = x
                nextState <- f.reduce(state, stateChanges)
              } yield (nextState, reply)
            }

        })
      }
    }

  def correlated[F[_]: Monad, Op[_]](entityBehavior: Op[_] => Behavior[F, Op]): Behavior[F, Op] =
    Behavior(Lambda[Op ~> PairT[F, Behavior[F, Op], ?]] { op =>
      entityBehavior(op).run(op)
    })
}
