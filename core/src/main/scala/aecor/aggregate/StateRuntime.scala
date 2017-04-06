package aecor.aggregate

import aecor.data.{ Correlation, Handler }
import cats.data._
import cats.implicits._
import cats.{ Monad, ~> }

import scala.collection.immutable.Seq

object StateRuntime {

  /**
    * Creates an aggregate runtime that uses StateT as a target context
    *
    * This runtime doesn't account for correlation,
    * i.e. all operations are executed against common sequence of events
    *
    */
  def shared[F[_]: Monad, Op[_], S, E](
    behavior: Op ~> Handler[F, S, Seq[E], ?]
  )(implicit folder: Folder[F, E, S]): Op ~> StateT[F, Vector[E], ?] =
    new (Op ~> StateT[F, Vector[E], ?]) {
      override def apply[A](fa: Op[A]): StateT[F, Vector[E], A] =
        for {
          events <- StateT.get[F, Vector[E]]
          state <- StateT.lift(folder.consume(events))
          result <- StateT.lift(behavior(fa).run(state)).flatMap {
                     case (es, r) =>
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
  def correlate[F[_]: Monad, O[_], E](
    behavior: O ~> StateT[F, Vector[E], ?],
    correlation: Correlation[O]
  ): O ~> StateT[F, Map[String, Vector[E]], ?] =
    new (O ~> StateT[F, Map[String, Vector[E]], ?]) {
      override def apply[A](fa: O[A]): StateT[F, Map[String, Vector[E]], A] = {
        val entityId = correlation(fa)
        behavior(fa).transformS(_.getOrElse(entityId, Vector.empty[E]), _.updated(entityId, _))
      }
    }

}
