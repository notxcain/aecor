package akka.persistence.cassandra.journal

import java.lang.{ Long => JLong }
import java.nio.ByteBuffer
import java.util.UUID

import aecor.aggregate.runtime.EventJournal.EventEnvelope
import aecor.aggregate.serialization.PersistentEncoder
import akka.Done
import akka.actor.{ Actor, ActorLogging, NoSerializationVerificationNeeded }
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.pattern.{ CircuitBreaker, pipe }
import akka.persistence.cassandra._
import akka.persistence.cassandra.journal.CassandraEventJournalActor._
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.serialization.SerializationExtension
import cats.data.NonEmptyVector
import cats.implicits._
import com.datastax.driver.core._
import com.datastax.driver.core.policies.{ LoggingRetryPolicy, RetryPolicy }
import com.datastax.driver.core.utils.UUIDs
import com.typesafe.config.Config

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS, _ }
import scala.util.{ Success, Try }

class CassandraEventJournalActor[E: PersistentEncoder](cfg: Config)
    extends Actor
    with ActorLogging
    with CassandraStatements {

  val config = new CassandraJournalConfig(context.system, cfg.getConfig("cassandra-journal"))
  val serialization = SerializationExtension(context.system)

  import config._
  import context.dispatcher

  // Announce written changes to DistributedPubSub if pubsub-minimum-interval is defined in config
  private val pubsub = pubsubMinimumInterval match {
    case interval: FiniteDuration =>
      // PubSub will be ignored when clustering is unavailable
      Try {
        DistributedPubSub(context.system)
      }.toOption flatMap { extension =>
        if (extension.isTerminated)
          None
        else
          Some(
            context.actorOf(
              PubSubThrottler
                .props(extension.mediator, interval)
                .withDispatcher(context.props.dispatcher)
            )
          )
      }

    case _ => None
  }

  val breaker = {
    val bcfg = cfg.withFallback(cfg.getConfig("akka.persistence.journal-plugin-fallback"))
    val maxFailures = bcfg.getInt("circuit-breaker.max-failures")
    val callTimeout = bcfg.getDuration("circuit-breaker.call-timeout", MILLISECONDS).millis
    val resetTimeout = bcfg.getDuration("circuit-breaker.reset-timeout", MILLISECONDS).millis
    CircuitBreaker(context.system.scheduler, maxFailures, callTimeout, resetTimeout)
  }

  val session = new CassandraSession(
    context.system,
    config.sessionProvider,
    config.sessionSettings,
    context.dispatcher,
    log,
    metricsCategory = s"${self.path.name}",
    init = session =>
      executeCreateKeyspaceAndTables(session, config, maxTagId)
        .flatMap(_ => initializePersistentConfig(session).map(_ => Done))
  )

  def preparedWriteMessage = session.prepare(writeMessage).map(_.setIdempotent(true))

  def preparedWriteInUse = session.prepare(writeInUse).map(_.setIdempotent(true))

  override def preStart(): Unit =
    // eager initialization, but not from constructor
    self ! CassandraEventJournalActor.Init

  def receive: Receive = {
    case WriteMessages(persistenceId, messages, instanceId) =>
      val writeResult = Future(
        breaker.withCircuitBreaker(
          asyncWriteMessages(
            AtomicWrite(
              persistenceId,
              messages.asInstanceOf[NonEmptyVector[EventEnvelope[E]]].toVector,
              instanceId
            )
          )
        )
      ).flatten
      writeResult.pipeTo(sender())
      ()

    case CassandraEventJournalActor.Init =>
      preparedWriteMessage
      preparedWriteInUse
      ()
  }

  private val writeRetryPolicy = new LoggingRetryPolicy(new FixedRetryPolicy(config.writeRetries))

  override def postStop(): Unit =
    session.close()

  private def asyncWriteMessages(aw: AtomicWrite[E]): Future[Unit] = {
    val serialized =
      SerializedAtomicWrite(
        aw.persistenceId,
        aw.payload.map { pr =>
          val repr = PersistentEncoder[E].encode(pr.event)
          val serManifest = repr.manifest
          val serEvent = ByteBuffer.wrap(repr.payload)
          Serialized(
            aw.persistenceId,
            pr.sequenceNr,
            serEvent,
            pr.tags,
            "",
            serManifest,
            100,
            aw.instanceId
          )
        }
      )

    def publishTagNotification(serialized: SerializedAtomicWrite): Unit =
      if (pubsub.isDefined) {
        for {
          p <- pubsub
          tag: String <- serialized.payload.map(_.tags).flatten.toSet
        } {
          p ! DistributedPubSubMediator.Publish("akka.persistence.cassandra.journal.tag", tag)
        }
      }

    writeMessages(serialized).andThen {
      case Success(_) =>
        publishTagNotification(serialized)
    }
  }

  private def writeMessages(atomicWrites: SerializedAtomicWrite): Future[Unit] = {
    val boundStatements = statementGroup(atomicWrites)
    boundStatements.size match {
      case 1 =>
        boundStatements.head.flatMap(execute(_, writeRetryPolicy))
      case 0 => Future.successful(())
      case _ =>
        Future.sequence(boundStatements).flatMap { stmts =>
          executeBatch(batch => stmts.foreach(batch.add), writeRetryPolicy)
        }
    }
  }

  private def statementGroup(atomicWrites: SerializedAtomicWrite): Seq[Future[BoundStatement]] = {
    val maxPnr = partitionNr(atomicWrites.payload.last.sequenceNr)
    val firstSeq = atomicWrites.payload.head.sequenceNr
    val minPnr = partitionNr(firstSeq)
    val persistenceId: String = atomicWrites.persistenceId
    val all = atomicWrites.payload

    // reading assumes sequence numbers are in the right partition or partition + 1
    // even if we did allow this it would perform terribly as large C* batches are not good
    require(
      maxPnr - minPnr <= 1,
      "Do not support AtomicWrites that span 3 partitions. Keep AtomicWrites <= max partition size."
    )

    val writes: Seq[Future[BoundStatement]] = all.map { m =>
      // use same clock source as the UUID for the timeBucket
      val nowUuid = UUIDs.timeBased()
      val now = UUIDs.unixTimestamp(nowUuid)
      preparedWriteMessage.map { stmt =>
        val bs = stmt.bind()
        bs.setString("persistence_id", persistenceId)
        bs.setLong("partition_nr", maxPnr)
        bs.setLong("sequence_nr", m.sequenceNr)
        bs.setUUID("timestamp", nowUuid)
        bs.setString("timebucket", TimeBucket(now).key)
        bs.setString("writer_uuid", m.writerUuid.toString)
        bs.setInt("ser_id", m.serId)
        bs.setString("ser_manifest", m.serManifest)
        bs.setString("event_manifest", m.eventManifest)
        bs.setBytes("event", m.serialized)

        if (session.protocolVersion.compareTo(ProtocolVersion.V4) < 0) {
          bs.setToNull("message")
          (1 to maxTagsPerEvent).foreach(tagId => bs.setToNull("tag" + tagId))
        } else {
          bs.unset("message")
        }

        if (m.tags.nonEmpty) {
          var tagCounts = Array.ofDim[Int](maxTagsPerEvent)
          m.tags.foreach { tag =>
            val tagId = tags.getOrElse(tag, 1)
            bs.setString("tag" + tagId, tag)
            tagCounts(tagId - 1) = tagCounts(tagId - 1) + 1
            var i = 0
            while (i < tagCounts.length) {
              if (tagCounts(i) > 1)
                log.warning(
                  "Duplicate tag identifer [{}] among tags [{}] for event from [{}]. " +
                    "Define known tags in cassandra-journal.tags configuration when using more than " +
                    "one tag per event.",
                  (i + 1),
                  m.tags.mkString(","),
                  persistenceId
                )
              i += 1
            }
          }
        }

        bs
      }
    }
    // in case we skip an entire partition we want to make sure the empty partition has in in-use flag so scans
    // keep going when they encounter it
    if (partitionNew(firstSeq) && minPnr != maxPnr)
      writes :+ preparedWriteInUse.map(_.bind(persistenceId, minPnr: JLong))
    else
      writes

  }

  def partitionNr(sequenceNr: Long): Long =
    (sequenceNr - 1L) / targetPartitionSize

  private def executeBatch(body: BatchStatement ⇒ Unit, retryPolicy: RetryPolicy): Future[Unit] = {
    val batch = new BatchStatement()
      .setConsistencyLevel(writeConsistency)
      .setRetryPolicy(retryPolicy)
      .asInstanceOf[BatchStatement]
    body(batch)
    session.underlying().flatMap(_.executeAsync(batch).asScala).map(_ => ())
  }

  private def execute(stmt: Statement, retryPolicy: RetryPolicy): Future[Unit] = {
    stmt.setConsistencyLevel(writeConsistency).setRetryPolicy(retryPolicy)
    session.executeWrite(stmt).map(_ => ())
  }

  private def partitionNew(sequenceNr: Long): Boolean =
    (sequenceNr - 1L) % targetPartitionSize == 0L
}

object CassandraEventJournalActor {
  private case object Init

  final case class WriteMessages[E](persistenceId: String,
                                    messages: NonEmptyVector[EventEnvelope[E]],
                                    instanceId: UUID)
      extends NoSerializationVerificationNeeded

  private case class AtomicWrite[E](persistenceId: String,
                                    payload: Seq[EventEnvelope[E]],
                                    instanceId: UUID)

  private case class SerializedAtomicWrite(persistenceId: String, payload: Seq[Serialized])

  private case class Serialized(persistenceId: String,
                                sequenceNr: Long,
                                serialized: ByteBuffer,
                                tags: Set[String],
                                eventManifest: String,
                                serManifest: String,
                                serId: Int,
                                writerUuid: UUID)
}
