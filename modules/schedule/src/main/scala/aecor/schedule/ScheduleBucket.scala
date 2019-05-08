package aecor.schedule

import java.time.{ LocalDateTime, ZoneOffset }

import aecor.encoding.WireProtocol
import aecor.macros.boopickle.BoopickleWireProtocol
import cats.tagless.{ Derive, FunctorK }

private[aecor] trait ScheduleBucket[F[_]] {
  def addScheduleEntry(entryId: String, correlationId: String, dueDate: LocalDateTime): F[Unit]

  def fireEntry(entryId: String): F[Unit]
}

private[aecor] object ScheduleBucket {
  import boopickle.Default._
  implicit val localDateTimePickler: Pickler[LocalDateTime] = transformPickler(
    (ldt: (Long, Int)) => LocalDateTime.ofEpochSecond(ldt._1, ldt._2, ZoneOffset.UTC)
  )((ldt: LocalDateTime) => (ldt.toEpochSecond(ZoneOffset.UTC), ldt.getNano))

  implicit def functorK: FunctorK[ScheduleBucket] = Derive.functorK
  implicit def wireProtocol: WireProtocol[ScheduleBucket] = BoopickleWireProtocol.derive
}
