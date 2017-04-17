package aecor.runtime.akkapersistence

import java.util.UUID

import aecor.data.Folded.{ Impossible, Next }
import aecor.data.{ Committable, Folded }
import aecor.effect.Async
import aecor.effect.Async.ops._
import akka.NotUsed
import akka.stream.scaladsl.Flow
import cats.Monad
import cats.implicits._
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

trait AggregateProjection[F[_], A, E, S] {
  def fetchVersionAndState(a: A)(event: E): F[(Long, Option[S])]

  def saveNewVersion(a: A)(s: S, version: Long): F[Unit]

  def applyEvent(a: A)(s: Option[S], event: E): Folded[Option[S]]
}

object AggregateProjection {
  val logger = LoggerFactory.getLogger(getClass)

  def flow[F[_]: Async: Monad, A, E, S](a: A)(
    implicit A: AggregateProjection[F, A, E, S],
    ec: ExecutionContext
  ): Flow[Committable[F, JournalEntry[UUID, E]], Unit, NotUsed] = {
    def applyEvent(event: E, sequenceNr: Long, state: Option[S]): F[Folded[Unit]] = {
      val newVersion = A.applyEvent(a)(state, event)
      logger.debug(s"New version [$newVersion]")
      newVersion
        .traverse {
          case Some(x) =>
            A.saveNewVersion(a)(x, sequenceNr + 1)
          case None =>
            ().pure[F]
        }

    }
    def applyJournalEntry(sequenceNr: Long, event: E): F[Either[String, Unit]] =
      A.fetchVersionAndState(a)(event)
        .flatMap {
          case (currentVersion, currentState) =>
            logger.debug(s"Current $currentVersion [$currentState]")
            if (currentVersion < sequenceNr) {
              applyEvent(event, currentVersion, currentState).map {
                case Next(_) => ().asRight[String]
                case Impossible =>
                  s"Projection failed for state = [$currentState], event = [$event]".asLeft[Unit]
              }
            } else {
              ().asRight[String].pure[F]
            }
        }

    Flow[Committable[F, JournalEntry[UUID, E]]]
      .mapAsync(1) { c =>
        c.traverse { entry =>
          logger.debug(s"Entry [$entry]")
          applyJournalEntry(entry.sequenceNr, entry.event).unsafeRun.flatMap {
            case Right(_) => Future.successful(())
            case Left(description) =>
              Future.failed(new IllegalStateException(description))
          }
        }
      }
      .mapAsync(1)(_.commit.unsafeRun)
      .named(s"AggregateProjection($a)")
  }
}
