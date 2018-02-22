package aecor

import cats.Id

package object data {

  /**
    * A transformer type representing a `(A, B)` wrapped in `F`
    */
  type PairT[F[_], A, B] = F[(A, B)]

  @deprecated("Use Action", "0.16.0")
  type Handler[S, E, A] = Action[S, E, A]

  type Action[S, E, A] = ActionT[Id, S, E, A]
  object Action {
    def apply[S, E, A](run: S => (List[E], A)): Action[S, E, A] = ActionT[Id, S, E, A](run)
    def read[S, E, A](f: S => A): Action[S, E, A] = Action(s => (List.empty[E], f(s)))
  }

  type EventsourcedBehavior[M[_[_]], S, E] = EventsourcedBehaviorT[M, Id, S, E]

  object EventsourcedBehavior {
    def apply[M[_[_]], S, E](actions: M[Action[S, E, ?]],
                             initialState: S,
                             applyEvent: (S, E) => Folded[S]): EventsourcedBehavior[M, S, E] =
      EventsourcedBehaviorT[M, Id, S, E](actions, initialState, applyEvent)

    def optional[M[_[_]], State, Event](
      actions: M[Action[Option[State], Event, ?]],
      init: Event => Folded[State],
      applyEvent: (State, Event) => Folded[State]
    ): EventsourcedBehavior[M, Option[State], Event] =
      EventsourcedBehavior(
        actions,
        Option.empty[State],
        (os, e) => os.map(s => applyEvent(s, e).map(Some(_))).getOrElse(Folded.impossible)
      )
  }
}
