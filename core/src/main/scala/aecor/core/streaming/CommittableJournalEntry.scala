package aecor.core.streaming

case class CommittableJournalEntry[Offset, +A](offset: CommittableOffset[Offset],
                                               persistenceId: String,
                                               sequenceNr: Long,
                                               value: A)
