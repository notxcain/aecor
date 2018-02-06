package aecor.data

final case class ConsumerId(value: String) extends AnyVal

final case class TagConsumer(tag: EventTag, consumerId: ConsumerId)
