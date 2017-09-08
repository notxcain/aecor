package aecor.tests.e2e

import aecor.tests.e2e.TestCounterViewRepository.TestCounterViewRepositoryState
import cats.Applicative
import cats.data.StateT

trait CounterViewRepository[F[_]] {
  def getCounterState(id: CounterId): F[Option[Long]]
  def setCounterState(id: CounterId, value: Long): F[Unit]
}

object TestCounterViewRepository {
  case class TestCounterViewRepositoryState(value: Map[CounterId, Long])
  object TestCounterViewRepositoryState {
    def init: TestCounterViewRepositoryState = TestCounterViewRepositoryState(Map.empty)
  }
  def apply[F[_]: Applicative, A](
    extract: A => TestCounterViewRepositoryState,
    update: (A, TestCounterViewRepositoryState) => A
  ): TestCounterViewRepository[F, A] =
    new TestCounterViewRepository(extract, update)
}

class TestCounterViewRepository[F[_]: Applicative, A](
  extract: A => TestCounterViewRepositoryState,
  update: (A, TestCounterViewRepositoryState) => A
) extends CounterViewRepository[StateT[F, A, ?]] {
  def getCounterState(id: CounterId): StateT[F, A, Option[Long]] =
    StateT
      .get[F, TestCounterViewRepositoryState]
      .map(_.value.get(id))
      .transformS(extract, update)
  def setCounterState(id: CounterId, value: Long): StateT[F, A, Unit] =
    StateT
      .modify[F, TestCounterViewRepositoryState](x => x.copy(x.value.updated(id, value)))
      .transformS(extract, update)
}
