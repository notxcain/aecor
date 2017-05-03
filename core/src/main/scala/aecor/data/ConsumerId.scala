package aecor.data

final case class ConsumerId(value: String) extends AnyVal

final case class TagConsumerId(tag: String, consumerId: ConsumerId)
