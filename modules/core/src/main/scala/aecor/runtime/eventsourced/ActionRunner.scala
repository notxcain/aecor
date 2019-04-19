package aecor.runtime.eventsourced

import aecor.data.ActionT
import aecor.runtime.Eventsourced.Versioned
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{ Monad, ~> }

object ActionRunner {
  def apply[F[_]: Monad, K, S, E](
    initial: S,
    stateStrategy: StateStrategyBuilder[K, F, S, E]
  ): K => F[ActionT[F, S, E, ?] ~> F] =
    key =>
      stateStrategy.create(key).map { ess =>
        Lambda[ActionT[F, S, E, ?] ~> F] { action =>
          for {
            current <- ess.recoverState(Versioned.zero(initial))
            (_, a) <- ess.run(current, action)
          } yield a
        }
    }

  def cached[F[_]: Sync, K, S, E](
    initial: S,
    stateStrategy: StateStrategyBuilder[K, F, S, E]
  ): K => F[ActionT[F, S, E, ?] ~> F] =
    key =>
      Ref[F].of(none[Versioned[S]]).flatMap { cache =>
        stateStrategy.create(key).map { ess =>
          Lambda[ActionT[F, S, E, ?] ~> F] { action =>
            for {
              recovered <- cache.get.flatMap {
                            case Some(s) => s.pure[F]
                            case None    => ess.recoverState(Versioned.zero(initial))
                          }
              (next, a) <- ess.run(recovered, action)
              _ <- cache.set(next.some)
            } yield a
          }
        }
    }

}
