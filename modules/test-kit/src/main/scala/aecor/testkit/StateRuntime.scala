package aecor.testkit

import aecor.data.{EventsourcedBehavior, Folded}
import io.aecor.liberator.Invocation
import aecor.data.Folded.{Impossible, Next}
import cats.data._
import cats.{Functor, MonadError, ~>}
import cats.implicits._
import io.aecor.liberator.{FunctorK, ReifiedInvocations}

object StateRuntime {

  /**
    *
    *  Construct runtime for single instance of aggregate
    *
    */
  def single[M[_[_]], F[_], S, E, R](
    behavior: EventsourcedBehavior[M, F, S, E]
  )(implicit F: MonadError[F, Throwable], M: ReifiedInvocations[M]): M[StateT[F, Chain[E], ?]] =
    M.mapInvocations {
      new (Invocation[M, ?] ~> StateT[F, Chain[E], ?]) {
        override def apply[A](op: Invocation[M, A]): StateT[F, Chain[E], A] =
          for {
            events <- StateT.get[F, Chain[E]]
            foldedState = events.foldM(behavior.initial)(behavior.update)
            result <- foldedState match {
                       case Next(state) =>
                         StateT.liftF(op.invoke(behavior.actions).run(state, behavior.update)).flatMap {
                           case Folded.Next((es, r)) =>
                             StateT
                               .modify[F, Chain[E]](_ ++ es)
                               .map(_ => r)
                           case Folded.Impossible =>
                             StateT.liftF[F, Chain[E], A](
                               F.raiseError(new IllegalStateException(s"Failed to fold $events"))
                             )
                         }
                       case Impossible =>
                         StateT.liftF[F, Chain[E], A](
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
    behavior: M[StateT[F, Chain[E], ?]]
  )(implicit M: FunctorK[M]): I => M[StateT[F, Map[I, Chain[E]], ?]] = { i =>
    val lift = Lambda[StateT[F, Chain[E], ?] ~> StateT[F, Map[I, Chain[E]], ?]](
      _.transformS(_.getOrElse(i, Chain.empty[E]), _.updated(i, _))
    )
    M.mapK(behavior, lift)
  }

}
