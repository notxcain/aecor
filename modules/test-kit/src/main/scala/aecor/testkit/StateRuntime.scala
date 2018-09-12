package aecor.testkit

import io.aecor.liberator.Invocation
import aecor.data.next.EventsourcedBehavior
import aecor.data.Folded.{ Impossible, Next }
import cats.data._
import cats.{ Functor, MonadError, ~> }
import cats.implicits._
import io.aecor.liberator.{ FunctorK, ReifiedInvocations }

object StateRuntime {

  /**
    *
    *  Construct runtime for single instance of aggregate
    *
    */
  def single[M[_[_]], F[_], S, E, R](
    behavior: EventsourcedBehavior[M, F, S, E, R]
  )(implicit F: MonadError[F, Throwable], M: ReifiedInvocations[M]): M[StateT[F, Vector[E], ?]] =
    M.mapInvocations {
      new (Invocation[M, ?] ~> StateT[F, Vector[E], ?]) {
        override def apply[A](op: Invocation[M, A]): StateT[F, Vector[E], A] =
          for {
            events <- StateT.get[F, Vector[E]]
            foldedState = events.foldM(behavior.initialState)(behavior.applyEvent)
            result <- foldedState match {
                       case Next(state) =>
                         StateT.liftF(op.invoke(behavior.actions).run(state, behavior.applyEvent)).flatMap {
                           case (es, r) =>
                             StateT
                               .modify[F, Vector[E]](_ ++ es)
                               .map(_ => r)
                         }
                       case Impossible =>
                         StateT.liftF[F, Vector[E], A](
                           F.raiseError(new IllegalStateException(s"Failed to fold $events"))
                         )
                     }

          } yield result
      }
    }

  /**
    * Creates an aggregate runtime that uses StateT as a target context
    *
    * This runtime uses supplied identifier
    * to execute commands against corresponding
    * sequence of events
    *
    */
  def route[M[_[_]], F[_]: Functor, E, I](
    behavior: M[StateT[F, Vector[E], ?]]
  )(implicit M: FunctorK[M]): I => M[StateT[F, Map[I, Vector[E]], ?]] = { i =>
    val lift = Lambda[StateT[F, Vector[E], ?] ~> StateT[F, Map[I, Vector[E]], ?]](
      _.transformS(_.getOrElse(i, Vector.empty[E]), _.updated(i, _))
    )
    M.mapK(behavior, lift)
  }

}
