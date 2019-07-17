package aecor.example.account
import java.util.UUID

import aecor.example.common.Timestamp
import aecor.runtime.Eventsourced
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime
import aecor.util.Clock
import cats.effect.{ ContextShift, Effect }
import cats.implicits._

object deployment {
  def deploy[F[_]: Effect: ContextShift](runtime: AkkaPersistenceRuntime[UUID],
                                         clock: Clock[F]): F[Accounts[F]] =
    runtime
      .deploy(
        "Account",
        EventsourcedAlgebra.behavior[F].enrich(clock.instant.map(Timestamp(_))),
        EventsourcedAlgebra.tagging
      )
      .map(Eventsourced.Entities.rejectable(_))
}
