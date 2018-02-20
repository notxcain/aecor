package aecor.runtime.akkapersistence.readside

import aecor.data.{ EntityEvent, EventTag }
import aecor.encoding.KeyDecoder
import aecor.runtime.akkapersistence.{ AkkaPersistenceRuntimeActor, JournalAdapter }
import aecor.runtime.akkapersistence.serialization.{ PersistentDecoder, PersistentRepr }
import akka.NotUsed
import akka.persistence.query._
import akka.stream.scaladsl.Source

import scala.concurrent.Future

private[akkapersistence] final class AkkaPersistenceEventJournalQuery[
  O, I: KeyDecoder, E: PersistentDecoder
](adapter: JournalAdapter[O])
    extends JournalQuery[O, I, E] {

  private val decoder = PersistentDecoder[E]
  private val keyDecoder = KeyDecoder[I]

  private val readJournal = adapter.createReadJournal

  private def createSource(
    inner: Source[EventEnvelope, NotUsed]
  ): Source[JournalEntry[O, I, E], NotUsed] =
    inner.mapAsync(1) {
      case EventEnvelope(offset, persistenceId, sequenceNr, event) =>
        offset match {
          case adapter.journalOffset(offsetValue) =>
            event match {
              case repr: PersistentRepr =>
                val index =
                  persistenceId.indexOf(AkkaPersistenceRuntimeActor.PersistenceIdSeparator)
                val idString = persistenceId.substring(index + 1, persistenceId.length)
                keyDecoder(idString) match {
                  case Some(id) =>
                    decoder
                      .decode(repr)
                      .right
                      .map { event =>
                        JournalEntry(offsetValue, EntityEvent(id, sequenceNr, event))
                      }
                      .fold(Future.failed, Future.successful)
                  case None =>
                    Future.failed(
                      new IllegalArgumentException(s"Failed to decode entity id [$idString]")
                    )
                }

              case other =>
                Future.failed(
                  new IllegalArgumentException(
                    s"Unexpected persistent representation [$other] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
                  )
                )
            }
          case other =>
            Future.failed(
              new IllegalArgumentException(
                s"Unexpected offset of type [${other.getClass}] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
              )
            )
        }
    }

  def eventsByTag(tag: EventTag, offset: Option[O]): Source[JournalEntry[O, I, E], NotUsed] =
    createSource(
      readJournal
        .eventsByTag(tag.value, adapter.journalOffset(offset))
    )

  override def currentEventsByTag(tag: EventTag,
                                  offset: Option[O]): Source[JournalEntry[O, I, E], NotUsed] =
    createSource(
      readJournal
        .currentEventsByTag(tag.value, adapter.journalOffset(offset))
    )

}

private[akkapersistence] object AkkaPersistenceEventJournalQuery {
  def apply[O, K: KeyDecoder, E: PersistentDecoder](
    adapter: JournalAdapter[O]
  ): JournalQuery[O, K, E] =
    new AkkaPersistenceEventJournalQuery(adapter)
}
