package aecor.example.transaction
import java.util.UUID

import aecor.example.common.Timestamp
import aecor.example.transaction.transaction.Transactions
import aecor.runtime.Eventsourced
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime
import aecor.util.Clock
import cats.implicits._
import cats.effect.Effect
import scodec.codecs.implicits._

object deployment {
  def deploy[F[_]: Effect](runtime: AkkaPersistenceRuntime[UUID],
                           clock: Clock[F]): F[Transactions[F]] =
    runtime
      .deploy(
        "Transaction",
        EventsourcedAlgebra.behavior[F].enrich(clock.instant.map(Timestamp(_))),
        EventsourcedAlgebra.tagging
      )
      .map(Eventsourced.Entities.fromEitherK(_))
}
