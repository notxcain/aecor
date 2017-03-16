package aecor.aggregate

import aecor.data.Handler
import cats.data._
import cats.implicits._
import cats.{ Monad, ~> }

object StateRuntime {

  /**
    * Creates an aggregate runtime that uses StateT as a target context
    *
    * This runtime doesn't account for correlation,
    * i.e. all operations are executed against common sequence of events
    *
    */
  def shared[Op[_], S, E, F[_]: Monad](
    behavior: Op ~> Handler[S, E, ?]
  )(implicit folder: Folder[F, E, S]): Op ~> StateT[F, Vector[E], ?] =
    new (Op ~> StateT[F, Vector[E], ?]) {
      override def apply[A](fa: Op[A]): StateT[F, Vector[E], A] =
        for {
          events <- StateT.get[F, Vector[E]]
          state <- StateT.lift(folder.consume(events))
          result <- {
            val (es, r) = behavior(fa).run(state)
            StateT.modify[F, Vector[E]](_ ++ es).map(_ => r)
          }
        } yield result
    }

  /**
    * Creates an aggregate runtime that uses StateT as a target context
    *
    * This runtime uses correlation function to get entity identifier
    * that is used to execute commands against corresponding
    * sequence of events
    *
    */
  def correlated[O[_], S, E, F[_]: Monad](
    behavior: O ~> Handler[S, E, ?],
    correlation: Correlation[O]
  )(implicit folder: Folder[F, E, S]): O ~> StateT[F, Map[String, Vector[E]], ?] =
    new (O ~> StateT[F, Map[String, Vector[E]], ?]) {
      override def apply[A](fa: O[A]): StateT[F, Map[String, Vector[E]], A] = {
        val inner: O ~> StateT[F, Vector[E], ?] = shared(behavior)
        val entityId = correlation(fa)
        inner(fa).transformS(_.getOrElse(entityId, Vector.empty[E]), _.updated(entityId, _))
      }
    }
}
