package aecor.testkit

import aecor.data.EventsourcedBehavior
import aecor.data.Folded.{ Impossible, Next }
import cats.data._
import cats.{ Functor, MonadError, ~> }
import cats.implicits._

object StateRuntime {

  /**
    *
    *  Construct runtime for single instance of aggregate
    *
    */
  def unit[F[_], Op[_], S, E](
    behavior: EventsourcedBehavior[F, Op, S, E]
  )(implicit F: MonadError[F, Throwable]): Op ~> StateT[F, Vector[E], ?] =
    new (Op ~> StateT[F, Vector[E], ?]) {
      override def apply[A](op: Op[A]): StateT[F, Vector[E], A] =
        for {
          events <- StateT.get[F, Vector[E]]
          foldedState = events.foldM(behavior.initialState)(behavior.applyEvent)
          result <- foldedState match {
                     case Next(state) =>
                       StateT.lift(behavior.commandHandler(op).run(state)).flatMap {
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
    * This runtime uses supplied identifier
    * to execute commands against corresponding
    * sequence of events
    *
    */
  def route[F[_]: Functor, I, O[_], E](
    behavior: O ~> StateT[F, Vector[E], ?]
  ): I => O ~> StateT[F, Map[I, Vector[E]], ?] =
    i =>
      behavior.andThen(
        Lambda[StateT[F, Vector[E], ?] ~> StateT[F, Map[I, Vector[E]], ?]](
          _.transformS(_.getOrElse(i, Vector.empty[E]), _.updated(i, _))
        )
    )

}
