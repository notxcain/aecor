package aecor.core.streaming

object CommittableJournalEntry {
  def unapply[A](arg: CommittableJournalEntry[A]): Option[(CommittableUUIDOffset, String, Long,  A)] =
    Some((arg._1, arg._2.persistenceId, arg._2.sequenceNr, arg._2.event))
}
