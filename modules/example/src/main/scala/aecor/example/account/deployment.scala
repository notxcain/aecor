package aecor.example.account
import java.util.UUID

import aecor.example.common.Timestamp
import aecor.runtime.Eventsourced
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime
import aecor.util.ClockT
import cats.effect.Effect
import cats.implicits._

object deployment {
  def deploy[F[_]: Effect](runtime: AkkaPersistenceRuntime[UUID], clock: ClockT[F]): F[Accounts[F]] =
      runtime
        .deploy(
          "Account",
          EventsourcedAlgebra.behavior[F].enrich(clock.instant.map(Timestamp(_))),
          EventsourcedAlgebra.tagging
        ).map(Eventsourced.Entity.fromEitherK(_))
}
