package aecor.example.transaction

import java.util.UUID

import aecor.example.common.Timestamp
import aecor.example.transaction.transaction.Transactions
import aecor.runtime.Eventsourced
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime
import aecor.util.Clock
import cats.effect.kernel.Async
import cats.implicits._
import scodec.codecs.implicits._

object deployment {
  def deploy[F[_]: Async](
      runtime: AkkaPersistenceRuntime[UUID],
      clock: Clock[F]
  ): F[Transactions[F]] =
    runtime
      .deploy(
        "Transaction",
        EventsourcedAlgebra.behavior[F].enrich(clock.instant.map(Timestamp(_))),
        EventsourcedAlgebra.tagging
      )
      .map(Eventsourced.Entities.rejectable(_))
      .allocated
      .map(_._1)
}
