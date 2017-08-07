package aecor.testkit

import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Correlation, EventsourcedBehavior }
import cats.data._
import cats.implicits._
import cats.{ Functor, MonadError, ~> }

object StateRuntime {

  /**
    * Creates an aggregate runtime that uses StateT as a target context
    *
    * This runtime doesn't account for correlation,
    * i.e. all operations are executed against common sequence of events
    *
    */
  def shared[F[_], Op[_], S, E](
    behavior: EventsourcedBehavior[F, Op, S, E]
  )(implicit F: MonadError[F, Throwable]): Op ~> StateT[F, Vector[E], ?] =
    new (Op ~> StateT[F, Vector[E], ?]) {
      override def apply[A](op: Op[A]): StateT[F, Vector[E], A] =
        for {
          events <- StateT.get[F, Vector[E]]
          foldedState = behavior.folder.consume(events)
          result <- foldedState match {
                     case Next(state) =>
                       StateT.lift(behavior.handler(op).run(state)).flatMap {
                         case (es, r) =>
                           StateT
                             .modify[F, Vector[E]](_ ++ es)
                             .map(_ => r)
                       }
                     case Impossible =>
                       StateT.lift[F, Vector[E], A](
                         F.raiseError(new IllegalStateException(s"Failed to fold $events"))
                       )
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
  def correlate[F[_]: Functor, O[_], E](
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
