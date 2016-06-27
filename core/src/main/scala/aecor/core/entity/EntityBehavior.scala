package aecor.core.entity

trait EntityBehavior[Entity, State, Command, Event, Rejection] {
  def initialState(entity: Entity): State
  def commandHandler(entity: Entity): CommandHandler[State, Command, Event, Rejection]
  def eventProjector(entity: Entity): EventProjector[State, Event]
}