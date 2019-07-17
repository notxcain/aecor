package aecor.schedule

import java.time._
import java.time.format.DateTimeFormatter

import aecor.schedule.CassandraScheduleEntryRepository.{ Queries, TimeBucket }
import aecor.schedule.ScheduleEntryRepository.ScheduleEntry
import aecor.util.effect._
import akka.NotUsed
import akka.persistence.cassandra._
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import cats.Monad
import cats.data.Kleisli
import cats.effect.Effect
import cats.implicits._
import com.datastax.driver.core.Row
import com.datastax.driver.extras.codecs.jdk8.InstantCodec
import org.slf4j.LoggerFactory

class CassandraScheduleEntryRepository[F[_]](cassandraSession: CassandraSession, queries: Queries)(
  implicit materializer: Materializer,
  F: Effect[F]
) extends ScheduleEntryRepository[F] {

  private val log = LoggerFactory.getLogger(classOf[CassandraScheduleEntryRepository[F]])
  private val preparedInsertEntry = cassandraSession.prepare(queries.insertEntry)
  private val preparedSelectEntries = cassandraSession.prepare(queries.selectEntries)
  private val preparedSelectEntry = cassandraSession.prepare(queries.selectEntry)

  override def insertScheduleEntry(id: ScheduleBucketId,
                                   entryId: String,
                                   dueDate: LocalDateTime): F[Unit] =
    F.fromFuture(preparedInsertEntry)
      .map(
        _.bind()
          .setString("schedule_name", id.scheduleName)
          .setString("schedule_bucket", id.scheduleBucket)
          .setString("entry_id", entryId)
          .setString("time_bucket", TimeBucket(dueDate.toLocalDate).key)
          .set("due_date", dueDate.toInstant(ZoneOffset.UTC), classOf[Instant])
          .setBool("fired", false)
      )
      .flatMap(x => F.fromFuture(cassandraSession.executeWrite(x)))
      .void

  override def markScheduleEntryAsFired(id: ScheduleBucketId, entryId: String): F[Unit] =
    F.fromFuture(preparedSelectEntry)
      .map(
        _.bind()
          .setString("schedule_name", id.scheduleName)
          .setString("schedule_bucket", id.scheduleBucket)
          .setString("entry_id", entryId)
      )
      .flatMap(x => F.fromFuture(cassandraSession.selectOne(x)))
      .flatMap {
        case Some(row) =>
          F.fromFuture(preparedInsertEntry)
            .map(
              _.bind()
                .setString("schedule_name", id.scheduleName)
                .setString("schedule_bucket", id.scheduleBucket)
                .setString("entry_id", entryId)
                .setString("time_bucket", row.getString("time_bucket"))
                .setTimestamp("due_date", row.getTimestamp("due_date"))
                .setBool("fired", true)
            )
            .flatMap(x => F.fromFuture(cassandraSession.executeWrite(x)))
            .void
        case None =>
          ().pure[F]
      }

  private def getBucket(timeBucket: TimeBucket,
                        from: LocalDateTime,
                        to: LocalDateTime): Source[ScheduleEntry, NotUsed] =
    Source
      .single(())
      .mapAsync(1) { _ =>
        preparedSelectEntries
      }
      .map(
        _.bind()
          .setString("time_bucket", timeBucket.key)
          .set("from_due_date", from.atOffset(ZoneOffset.UTC).toInstant, classOf[Instant])
          .set("to_due_date", to.atOffset(ZoneOffset.UTC).toInstant, classOf[Instant])
      )
      .flatMapConcat(cassandraSession.select)
      .map(fromRow)
      .mapMaterializedValue(_ => NotUsed)
      .named(s"getBucket($timeBucket, $from, $to)")

  private def getEntries(
    from: LocalDateTime,
    to: LocalDateTime
  ): Source[ScheduleEntryRepository.ScheduleEntry, NotUsed] =
    if (to isBefore from) {
      getEntries(to, from)
    } else {
      def rec(bucket: TimeBucket): Source[ScheduleEntry, NotUsed] = {
        log.debug("Querying bucket [{}] from [{}] to [{}]", bucket, from, to)
        getBucket(bucket, from, to).concat {
          Source.lazily { () =>
            val nextBucket = bucket.next
            if (nextBucket.isAfter(to.toLocalDate)) {
              Source.empty
            } else {
              rec(nextBucket)
            }
          }
        }
      }
      rec(TimeBucket(from.toLocalDate)).named(s"getEntries($from, $to)")
    }

  override def processEntries(from: LocalDateTime, to: LocalDateTime, parallelism: Int)(
    f: (ScheduleEntry) => F[Unit]
  ): F[Option[ScheduleEntry]] =
    F.fromFuture {
      getEntries(from, to)
        .mapAsync(parallelism)(x => f(x).as(x).unsafeToFuture())
        .runWith(Sink.lastOption)
    }

  private def fromRow(row: Row): ScheduleEntry =
    ScheduleEntry(
      ScheduleBucketId(row.getString("schedule_name"), row.getString("schedule_bucket")),
      row.getString("entry_id"),
      LocalDateTime.ofInstant(row.get("due_date", classOf[Instant]), ZoneOffset.UTC),
      row.getString("time_bucket"),
      row.getBool("fired")
    )
}

object CassandraScheduleEntryRepository {

  def apply[F[_]: Effect](cassandraSession: CassandraSession, queries: Queries)(
    implicit materializer: Materializer
  ): CassandraScheduleEntryRepository[F] =
    new CassandraScheduleEntryRepository(cassandraSession, queries)

  final case class TimeBucket(day: LocalDate, key: String) {
    def next: TimeBucket =
      TimeBucket(day.plusDays(1))

    def isAfter(other: LocalDate): Boolean =
      day.isAfter(other)

    def isBefore(other: LocalDate): Boolean =
      day.isBefore(other)

    override def toString: String = key
  }

  object TimeBucket {
    private val timeBucketFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

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
           schedule_bucket text,
           entry_id text,
           time_bucket text,
           due_date timestamp,
           fired boolean,
           PRIMARY KEY ((schedule_name, schedule_bucket), entry_id, time_bucket, due_date)
         )
       """
    val createMaterializedView: String =
      s"""
         CREATE MATERIALIZED VIEW IF NOT EXISTS $keyspace.$materializedViewName
         AS SELECT time_bucket, schedule_name, schedule_bucket, entry_id, due_date, fired FROM schedule_entries
         WHERE
           time_bucket IS NOT NULL
           AND due_date IS NOT NULL
           AND schedule_name IS NOT NULL
           AND schedule_bucket IS NOT NULL
           AND entry_id IS NOT NULL
         PRIMARY KEY (time_bucket, due_date, schedule_name, schedule_bucket, entry_id)
         WITH CLUSTERING ORDER BY (due_date ASC);
       """

    def insertEntry: String =
      s"""
         INSERT INTO $keyspace.$tableName (schedule_name, schedule_bucket, entry_id, time_bucket, due_date, fired)
         VALUES (:schedule_name, :schedule_bucket, :entry_id, :time_bucket, :due_date, :fired);
       """

    def selectEntry: String =
      s"""
         SELECT * FROM $keyspace.$tableName
         WHERE schedule_name = :schedule_name
           AND schedule_bucket = :schedule_bucket
           AND entry_id = :entry_id
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
  def init[F[_]](queries: Queries)(implicit F: Monad[F]): Session.Init[F] = Kleisli { session =>
    for {
      _ <- session.execute(queries.createTable)
      _ <- session.execute(queries.createMaterializedView)
      _ <- session.registerCodec(InstantCodec.instance)
    } yield ()
  }
}
