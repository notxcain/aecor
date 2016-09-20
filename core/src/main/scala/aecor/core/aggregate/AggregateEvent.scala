package aecor.core.aggregate

import java.time.Instant

case class AggregateEvent[+Event](id: EventId, event: Event, timestamp: Instant)
