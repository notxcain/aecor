package aecor.example.transaction
import java.util.UUID

import aecor.data.{EventTag, Tagging}
import aecor.example.common.Timestamp
import aecor.example.transaction.transaction.Transactions
import aecor.runtime.Eventsourced
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime
import aecor.util.ClockT
import cats.implicits._
import cats.effect.Effect

object deployment {
  def deploy[F[_]: Effect](runtime: AkkaPersistenceRuntime[UUID], clock: ClockT[F]): F[Transactions[F]] =
    runtime
      .deploy(
        "Account",
        EventsourcedAlgebra.behavior[F].enrich(clock.instant.map(Timestamp(_))),
        Tagging.const[TransactionId](EventTag("Transaction"))
      ).map(Eventsourced.Entity.fromEitherK(_))
}
