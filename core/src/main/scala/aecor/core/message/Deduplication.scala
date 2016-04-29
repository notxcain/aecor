package aecor.core.message

trait Deduplication {
  private val processedMessages = scala.collection.mutable.Set.empty[MessageId]
  def isProcessed(messageId: MessageId): Boolean = {
    processedMessages.contains(messageId)
  }
  def confirmProcessed(messageId: MessageId): Unit = {
    processedMessages += messageId
    ()
  }
}
