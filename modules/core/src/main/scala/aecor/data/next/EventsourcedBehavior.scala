package aecor.data.next
import aecor.Has
import aecor.data.Folded

final case class EventsourcedBehavior[M[_[_]], F[_], S, E, R](actions: M[ActionT[F, S, E, R, ?]],
                                                            initialState: S,
                                                            applyEvent: (S, E) => Folded[S])

object EventsourcedBehavior {
  def optional[M[_[_]], F[_], State, Event, Rejection](
                                       actions: M[ActionT[F, Option[State], Event, Rejection, ?]],
                                       init: Event => Folded[State],
                                       applyEvent: (State, Event) => Folded[State]
                                     ): EventsourcedBehavior[M, F, Option[State], Event, Rejection] =
    EventsourcedBehavior(
      actions,
      Option.empty[State],
      (os, e) => os.map(s => applyEvent(s, e)).getOrElse(init(e)).map(Some(_))
    )
}

final case class Enriched[M, E](metadata: M, event: E)
object Enriched {
  implicit def hasMetadata[M, E, X](implicit M: Has[M, X]): Has[Enriched[M, E], X] =
    M.contramap(_.metadata)
  implicit def hasEvent[M, E, X](implicit E: Has[E, X]): Has[Enriched[M, E], X] =
    E.contramap(_.event)
}
