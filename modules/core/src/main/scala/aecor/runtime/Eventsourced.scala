package aecor.runtime

import java.nio.ByteBuffer

import aecor.data.{ Folded, PairE }
import aecor.data.Folded.{ Impossible, Next }
import aecor.data.next.MonadActionRun.ActionFailure
import aecor.data.next._
import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.Decoder.DecodingResult
import aecor.encoding.WireProtocol.{ Decoder, Encoded, Encoder }
import cats.data.{ Chain, EitherT, NonEmptyChain }
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{ MonadError, ~> }
import io.aecor.liberator.syntax._
import io.aecor.liberator.{ Invocation, ReifiedInvocations }

final class Rejectable[M[_[_]], F[_], R] private (val value: M[EitherT[F, R, ?]]) extends AnyVal

object Rejectable {
  def apply[M[_[_]], F[_], R](m: M[EitherT[F, R, ?]]): Rejectable[M, F, R] = new Rejectable(m)

  implicit def wireProtocol[M[_[_]], R, F[_]](
    implicit M: WireProtocol[M],
    rdec: Decoder[R],
    renc: Encoder[R]
  ): WireProtocol[Rejectable[M, ?[_], R]] =
    new WireProtocol[Rejectable[M, ?[_], R]] {

      override def mapK[G[_], H[_]](mf: Rejectable[M, G, R], fg: ~>[G, H]): Rejectable[M, H, R] =
        Rejectable(mf.value.mapK(Lambda[EitherT[G, R, ?] ~> EitherT[H, R, ?]](_.mapK(fg))))

      override def invocations: Rejectable[M, Invocation[Rejectable[M, ?[_], R], ?], R] =
        Rejectable {
          M.mapInvocations(
            new (Invocation[M, ?] ~> EitherT[Invocation[Rejectable[M, ?[_], R], ?], R, ?]) {
              override def apply[A](
                fa: Invocation[M, A]
              ): EitherT[Invocation[Rejectable[M, ?[_], R], ?], R, A] =
                EitherT {
                  new Invocation[Rejectable[M, ?[_], R], Either[R, A]] {
                    override def invoke[G[_]](target: Rejectable[M, G, R]): G[Either[R, A]] =
                      fa.invoke(target.value).value
                  }
                }
            }
          )
        }

      override def encoder: Rejectable[M, Encoded, R] =
        Rejectable[M, Encoded, R] {
          M.mapInvocations(new (Invocation[M, ?] ~> EitherT[Encoded, R, ?]) {
            override def apply[A](ma: Invocation[M, A]): EitherT[Encoded, R, A] =
              EitherT[Encoded, R, A] {
                val (bytes, decM) = ma.invoke(M.encoder)
                val dec = new Decoder[Either[R, A]] {
                  override def decode(bytes: ByteBuffer): DecodingResult[Either[R, A]] = {
                    val success = bytes.get() == 1
                    if (success) {
                      decM.decode(bytes.slice()).map(_.asRight[R])
                    } else {
                      rdec.decode(bytes.slice()).map(_.asLeft[A])
                    }
                  }
                }
                (bytes, dec)
              }
          })
        }
      override def decoder
        : Decoder[PairE[Invocation[Rejectable[M, ?[_], R], ?], WireProtocol.Encoder]] =
        new Decoder[PairE[Invocation[Rejectable[M, ?[_], R], ?], WireProtocol.Encoder]] {
          override def decode(
            bytes: ByteBuffer
          ): DecodingResult[PairE[Invocation[Rejectable[M, ?[_], R], ?], WireProtocol.Encoder]] =
            M.decoder.decode(bytes).map { p =>
              val (invocation, encoder) = (p.first, p.second)

              val invocationR =
                new Invocation[Rejectable[M, ?[_], R], Either[R, p.A]] {
                  override def invoke[G[_]](target: Rejectable[M, G, R]): G[Either[R, p.A]] =
                    invocation.invoke(target.value).value
                }

              val encoderR = Encoder.instance[Either[R, p.A]] {
                case Right(a) =>
                  val bytes = encoder.encode(a)
                  val out = ByteBuffer.allocate(1 + bytes.limit())
                  out.put(1: Byte)
                  out.put(bytes)
                  out
                case Left(r) =>
                  val bytes = renc.encode(r)
                  val out = ByteBuffer.allocate(1 + bytes.limit())
                  out.put(0: Byte)
                  out.put(bytes)
                  out
              }

              PairE(invocationR, encoderR)
            }
        }
    }
}

object Eventsourced {
  final case class Snapshotting[F[_], K, S](snapshotEach: Long,
                                            store: KeyValueStore[F, K, InternalState[S]])
  type EntityId = String
  final case class InternalState[S](entityState: S, version: Long)

  sealed abstract class BehaviorFailure extends Throwable
  object BehaviorFailure {
    def illegalFold(entityId: EntityId): BehaviorFailure = IllegalFold(entityId)
    final case class IllegalFold(entityId: EntityId) extends BehaviorFailure
  }

  trait FailureHandler[F[_]] {
    def fail[A](e: BehaviorFailure): F[A]
  }

  object FailureHandler {
    implicit def monadErrorFailure[F[_]](implicit F: MonadError[F, Throwable]): FailureHandler[F] =
      new FailureHandler[F] {
        override def fail[A](e: BehaviorFailure): F[A] = F.raiseError(e)
      }
  }

  def apply[M[_[_]], F[_]: Sync, S, E, R, K](
    entityBehavior: EventsourcedBehavior[M, F, S, E, R],
    journal: EventJournal[F, K, E],
    snapshotting: Option[Snapshotting[F, K, S]] = Option.empty
  )(implicit M: ReifiedInvocations[M], F: FailureHandler[F]): K => F[Rejectable[M, R, F]] = { key =>
    val initialState = InternalState(entityBehavior.initialState, 0)

    val effectiveUpdate = (s: InternalState[S], e: E) =>
      entityBehavior
        .applyEvent(s.entityState, e)
        .map(InternalState(_, s.version + 1))

    val needsSnapshot = snapshotting match {
      case Some(Snapshotting(x, _)) =>
        (state: InternalState[S]) =>
          state.version % x == 0
      case None =>
        (_: InternalState[S]) =>
          false
    }

    val snapshotStore =
      snapshotting.map(_.store).getOrElse(NoopKeyValueStore[F, K, InternalState[S]])

    def loadState: F[InternalState[S]] =
      for {
        snapshot <- snapshotStore.getValue(key)
        effectiveInitialState = snapshot.getOrElse(initialState)
        out <- journal
                .foldById(key, effectiveInitialState.version + 1, effectiveInitialState)(
                  effectiveUpdate
                )
                .flatMap {
                  case Next(x) => x.pure[F]
                  case Impossible =>
                    F.fail[InternalState[S]](
                      BehaviorFailure
                        .illegalFold(key.toString)
                    )
                }
      } yield out

    def updateState(state: InternalState[S], events: Chain[E]) =
      if (events.isEmpty) {
        state.pure[F]
      } else {
        val folded: Folded[(Boolean, InternalState[S])] =
          events.foldM((false, state)) {
            case ((snapshotPending, s), e) =>
              effectiveUpdate(s, e).map { next =>
                (snapshotPending || needsSnapshot(next), next)
              }
          }
        folded match {
          case Next((snapshotNeeded, nextState)) =>
            val appendEvents = journal
              .append(key, state.version + 1, NonEmptyChain.fromChainUnsafe(events))
            val snapshotIfNeeded = if (snapshotNeeded) {
              snapshotStore.setValue(key, nextState)
            } else {
              ().pure[F]
            }
            (appendEvents, snapshotIfNeeded).mapN((_, _) => nextState)
          case Impossible =>
            F.fail[InternalState[S]](
              BehaviorFailure
                .illegalFold(key.toString)
            )
        }
      }

    for {
      initialState <- loadState
      ref <- Ref[F].of(initialState)
    } yield
      Rejectable(M.mapInvocations(new (Invocation[M, ?] ~> EitherT[F, R, ?]) {
        override def apply[A](fa: Invocation[M, A]): EitherT[F, R, A] = EitherT {
          for {
            current <- ref.get
            action = fa.invoke(entityBehavior.actions)
            result <- action.run(current.entityState, entityBehavior.applyEvent)
            _ <- result match {
                  case Left(ActionFailure.ImpossibleFold) =>
                    F.fail(BehaviorFailure.illegalFold(key.toString))
                  case Left(ActionFailure.Rejection(r)) =>
                    r.asLeft[A].pure[F]
                  case Right((es, a)) =>
                    updateState(current, es).flatMap(ref.set).as(a.asRight[R])
                }
          } yield null
        }
      }))
  }
}
