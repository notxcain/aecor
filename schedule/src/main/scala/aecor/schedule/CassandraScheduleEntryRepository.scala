package aecor.schedule

import java.time._
import java.time.format.DateTimeFormatter
import java.util.{ Date, UUID }

import aecor.schedule.CassandraScheduleEntryRepository.{ Queries, TimeBucket }
import aecor.schedule.ScheduleEntryRepository.ScheduleEntryView
import akka.NotUsed
import akka.persistence.cassandra._
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.stream.scaladsl.Source
import com.datastax.driver.core.utils.UUIDs
import com.datastax.driver.core.{ Row, Session }

import scala.concurrent.{ ExecutionContext, Future }

class CassandraScheduleEntryRepository(cassandraSession: CassandraSession, queries: Queries)(
  implicit executionContext: ExecutionContext
) extends ScheduleEntryRepository {
  private val preparedInsertEntry = cassandraSession.prepare(queries.insertEntry)
  private val preparedSelectEntries = cassandraSession.prepare(queries.selectEntries)

  override def insertScheduleEntry(scheduleName: String,
                                   entryId: String,
                                   dueDate: LocalDateTime): Future[Unit] =
    preparedInsertEntry
      .map(
        _.bind()
          .setString("schedule_name", scheduleName)
          .setString("entry_id", entryId)
          .setString("time_bucket", TimeBucket(dueDate.toLocalDate).key)
          .setTimestamp("due_date", {
            Date.from(dueDate.atOffset(ZoneOffset.UTC).toInstant)
          })
      )
      .flatMap(cassandraSession.executeWrite)
      .map(_ => ())

  def getBucket(timeBucket: TimeBucket,
                from: LocalDateTime,
                to: LocalDateTime): Source[ScheduleEntryView, NotUsed] =
    Source
      .fromFuture(
        preparedSelectEntries.map(
          _.bind()
            .setString("time_bucket", timeBucket.key)
            .setTimestamp("from_due_date", Date.from(from.atOffset(ZoneOffset.UTC).toInstant))
            .setTimestamp("to_due_date", Date.from(to.atOffset(ZoneOffset.UTC).toInstant))
        )
      )
      .flatMapConcat(cassandraSession.select)
      .map(fromRow)

  override def getEntries(
    from: LocalDateTime,
    to: LocalDateTime
  ): Source[ScheduleEntryRepository.ScheduleEntryView, NotUsed] =
    if (to isBefore from) {
      getEntries(to, from)
    } else {
      def rec(bucket: TimeBucket): Source[ScheduleEntryView, NotUsed] =
        getBucket(bucket, from, to).concat {
          Source.lazily { () =>
            val nextBucket = bucket.next
            if (nextBucket.day.isAfter(to.toLocalDate)) {
              Source.empty
            } else {
              rec(nextBucket)
            }
          }
        }
      rec(TimeBucket(from.toLocalDate))
    }

  private def fromRow(row: Row): ScheduleEntryView =
    ScheduleEntryView(
      row.getString("schedule_name"),
      row.getString("entry_id"),
      LocalDateTime.ofInstant(row.getTimestamp("due_date").toInstant, ZoneOffset.UTC),
      row.getString("time_bucket")
    )
}

object CassandraScheduleEntryRepository {
  final case class TimeBucket(day: LocalDate, key: String) {
    def next: TimeBucket =
      TimeBucket(day.plusDays(1))

    def isBefore(other: LocalDate): Boolean =
      day.isBefore(other)

    def startTimestamp: Long =
      day.atStartOfDay.toInstant(ZoneOffset.UTC).toEpochMilli

    override def toString: String = key
  }

  object TimeBucket {
    private val timeBucketFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    def apply(key: String): TimeBucket =
      apply(LocalDate.parse(key, timeBucketFormatter), key)

    def apply(timeuuid: UUID): TimeBucket =
      apply(UUIDs.unixTimestamp(timeuuid))

    def apply(epochTimestamp: Long): TimeBucket = {
      val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochTimestamp), ZoneOffset.UTC)
      apply(time.toLocalDate)
    }

    def apply(day: LocalDate): TimeBucket = {
      val key = day.format(timeBucketFormatter)
      apply(day, key)
    }

  }

  final case class Queries(keyspace: String, tableName: String) {
    val materializedViewName = s"${tableName}_by_time_bucket"
    val createTable: String =
      s"""
         CREATE TABLE IF NOT EXISTS $keyspace.$tableName (
           schedule_name text,
           entry_id text,
           time_bucket text,
           due_date timestamp,
           PRIMARY KEY ((schedule_name, entry_id), due_date, time_bucket)
         )
       """
    val createMaterializedView: String =
      s"""
         CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.$materializedViewName
         AS SELECT * FROM schedule_entries
         WHERE
           time_bucket IS NOT NULL
           AND due_date IS NOT NULL
           AND schedule_name IS NOT NULL
           AND entry_id IS NOT NULL
         PRIMARY KEY (time_bucket, due_date, schedule_name, entry_id)
         WITH CLUSTERING ORDER BY (due_date ASC, timestamp ASC);
       """

    def insertEntry: String =
      s"""
         INSERT INTO $keyspace.$tableName (schedule_name, entry_id, time_bucket, due_date)
         VALUES (:schedule_name, :entry_id, :time_bucket, :due_date);
       """

    def selectEntry: String =
      s"""
         SELECT * FROM $keyspace.$tableName WHERE schedule_name = :schedule_name AND entry_id = :entry_id
       """

    def selectEntries: String =
      s"""
         SELECT * FROM $keyspace.$materializedViewName
         WHERE
           time_bucket = :time_bucket
           AND due_date > :from_due_date
           AND due_date <= :to_due_date
         ORDER BY due_date ASC;
       """
  }
  def init(
    queries: Queries
  )(implicit executionContext: ExecutionContext): Session => Future[Unit] = { session =>
    for {
      _ <- session.executeAsync(queries.createTable).asScala
      _ <- session.executeAsync(queries.createMaterializedView).asScala
    } yield ()
  }
}
