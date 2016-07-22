package aecor.core.streaming

import aecor.core.aggregate.AggregateEventEnvelope
import aecor.core.serialization.Encoder
import aecor.core.serialization.protobuf.EventEnvelope
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.Flow
import com.google.protobuf.ByteString
import org.apache.kafka.clients.producer.ProducerRecord


object Kafka {
  def eventSink[A: Encoder](producerSettings: ProducerSettings[String, EventEnvelope], topic: String) = Flow[CommittableJournalEntry[AggregateEventEnvelope[A]]].map {
    case CommittableJournalEntry(offset, persistenceId, sequenceNr, AggregateEventEnvelope(eventId, event, timestamp, causedBy)) =>
      val payload = EventEnvelope(eventId.value, persistenceId, sequenceNr, ByteString.copyFrom(Encoder[A].encode(event)), timestamp.toEpochMilli)
      val producerRecord = new ProducerRecord(topic, null, payload.timestamp, persistenceId, payload)
      ProducerMessage.Message(producerRecord, offset)
  }.to(Producer.commitableSink(producerSettings))
}
