package aecor.core.streaming


case class CommittableJournalEntry[+A](committableOffset: CommittableUUIDOffset, persistenceId: String, sequenceNr: Long, value: A)