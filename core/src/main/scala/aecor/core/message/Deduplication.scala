package aecor.core.message

private [aecor] trait Deduplication[Id] {
  private val processedMessages = scala.collection.mutable.Set.empty[Id]
  def isProcessed(messageId: Id): Boolean = {
    processedMessages.contains(messageId)
  }
  def confirmProcessed(messageId: Id): Unit = {
    processedMessages += messageId
    ()
  }
}
