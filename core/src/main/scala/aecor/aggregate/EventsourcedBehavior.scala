package aecor.aggregate

import aecor.data.{ Folded, Handler }
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Source }
import cats.arrow.FunctionK
import cats.~>

import scala.collection.immutable.Seq
import scala.concurrent.Future

object EventsourcedBehavior {
  def apply[J, Op[_], S, E](journal: J, operations: Op ~> Handler[S, E, ?])(
    implicit J: EventJournal[J, E, Future, Source[?, NotUsed]],
    S: Folder[Folded, E, S],
    materializer: Materializer
  ): String => Future[Behavior[Op, Future]] = { id =>
    J.load(journal)(id)
      .scanAsync(S.zero) { (next, event) =>
        S.fold(next, event)
          .fold(
            Future.failed(new IllegalStateException(s"Recovery error for [$id],")),
            Future.successful
          )
      }
      .runFold(S.zero)(Keep.right)
      .map { recoveredState =>
        def withState(state: S): Behavior[Op, Future] = {
          def mk[A](op: Op[A]): Future[(Behavior[Op, Future], A)] = {
            val (events, reply) = operations(op).run(state)
            J.append(journal)(id, events)
              .flatMap { _ =>
                foldUntilImpossible(events)(state)(S.fold).fold(
                  Future.failed(new IllegalStateException(s"Recovery error for [$id],")),
                  Future.successful
                )
              }
              .map(s => (withState(s), reply))
          }
          Behavior(FunctionK.lift(mk _))
        }
        withState(recoveredState)
      }
  }

  def foldUntilImpossible[A, B](as: Seq[A])(zero: B)(f: (B, A) => Folded[B]): Folded[B] =
    if (as.isEmpty) {
      Folded.next(zero)
    } else {
      f(zero, as.head).flatMap(foldUntilImpossible(as.tail)(_)(f))
    }
}
