package aecor.experimental

import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Correlation, EventsourcedBehavior, Folder, Handler }
import aecor.experimental.Eventsourced.BehaviorFailure
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
    evensourcedBehavior: EventsourcedBehavior[F, Op, S, E]
  ): Op ~> StateT[EitherT[F, BehaviorFailure, ?], Vector[E], ?] =
    new (Op ~> StateT[EitherT[F, BehaviorFailure, ?], Vector[E], ?]) {
      override def apply[A](op: Op[A]): StateT[EitherT[F, BehaviorFailure, ?], Vector[E], A] =
        for {
          events <- StateT.get[EitherT[F, BehaviorFailure, ?], Vector[E]]
          foldedState = evensourcedBehavior.folder.consume(events)
          result <- foldedState match {
                     case Next(state) =>
                       val (es, r) = evensourcedBehavior.handler(op).run(state)
                       StateT
                         .modify[EitherT[F, BehaviorFailure, ?], Vector[E]](_ ++ es)
                         .map(_ => r)
                     case Impossible =>
                       StateT.lift[EitherT[F, BehaviorFailure, ?], Vector[E], A](
                         EitherT.left(BehaviorFailure.illegalFold("unknown").pure[F])
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
