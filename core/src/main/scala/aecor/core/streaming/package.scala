package aecor.core

package object streaming {
  type CommittableJournalEntry[+A] = (CommittableUUIDOffset, JournalEntry[A])
}
