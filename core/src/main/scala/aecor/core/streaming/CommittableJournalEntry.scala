package aecor.core.streaming

case class CommittableJournalEntry[+A](committableOffset: CommittableOffset,
                                       persistenceId: String,
                                       sequenceNr: Long,
                                       value: A)
