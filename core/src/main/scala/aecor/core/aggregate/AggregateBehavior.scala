package aecor.core.aggregate

trait AggregateBehavior[Aggregate, State, Command, Event, Rejection] {
  def initialState(entity: Aggregate): State
  def commandHandler(entity: Aggregate): CommandHandler[State, Command, Event, Rejection]
  def eventProjector(entity: Aggregate): EventProjector[State, Event]
}