package aecor.tests.e2e

import aecor.tests.e2e.TestCounterViewRepository.State
import cats.mtl.MonadState
import monocle.Lens
import aecor.testkit._

trait CounterViewRepository[F[_]] {
  def getCounterState(id: CounterId): F[Option[Long]]
  def setCounterState(id: CounterId, value: Long): F[Unit]
}

object TestCounterViewRepository {
  case class State(values: Map[CounterId, Long]) {
    def getCounterState(id: CounterId): Option[Long] =
      values.get(id)
    def setCounterState(id: CounterId, value: Long): State =
      State(values.updated(id, value))
  }
  object State {
    def init: State = State(Map.empty)
  }
  final class Builder[F[_]] {
    def apply[S: MonadState[F, ?]](lens: Lens[S, State]): TestCounterViewRepository[F, S] =
      new TestCounterViewRepository(lens)
  }
  def apply[F[_]]: Builder[F] = new Builder[F]
}

class TestCounterViewRepository[F[_]: MonadState[?[_], S], S](lens: Lens[S, State])
    extends CounterViewRepository[F] {
  private val F = lens.transformMonadState(MonadState[F, S])
  def getCounterState(id: CounterId): F[Option[Long]] =
    F.inspect(_.getCounterState(id))
  def setCounterState(id: CounterId, value: Long): F[Unit] =
    F.modify(_.setCounterState(id, value))
}
