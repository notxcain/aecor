package aecor.example.account

import java.util.UUID

import aecor.example.common.Timestamp
import aecor.runtime.akkapersistence.AkkaPersistenceRuntime
import aecor.runtime.Eventsourced
import aecor.util.Clock
import cats.effect.kernel.Async
import cats.syntax.all._

object deployment {
  def deploy[F[_]: Async](runtime: AkkaPersistenceRuntime[UUID], clock: Clock[F]): F[Accounts[F]] =
    runtime
      .deploy(
        "Account",
        EventsourcedAlgebra.behavior[F].enrich(clock.instant.map(Timestamp(_))),
        EventsourcedAlgebra.tagging
      )
      .map(Eventsourced.Entities.rejectable(_))
      .allocated
      .map(_._1)
}
