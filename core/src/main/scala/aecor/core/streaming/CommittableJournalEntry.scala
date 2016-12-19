package aecor.core.streaming

case class CommittableJournalEntry[Offset, +A](
    committableOffset: CommittableOffset[Offset],
    persistenceId: String,
    sequenceNr: Long,
    value: A)
