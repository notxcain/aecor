package akka.persistence.cassandra

import java.util.concurrent.Executor

import cats.data.Kleisli
import cats.effect.Async
import cats.effect.syntax.async._
import com.datastax.driver.core.{ ResultSet, Session => DatastaxSession, TypeCodec }

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

trait Session[F[_]] {
  def execute(query: String): F[ResultSet]
  def registerCodec[A](codec: TypeCodec[A]): F[Unit]
}

object Session {
  type Init[F[_]] = Kleisli[F, Session[F], Unit]
  def Init[F[_]](f: Session[F] => F[Unit]): Init[F] = Kleisli(f)
  private val immediateExecutor = new Executor {
    override def execute(command: Runnable): Unit =
      command.run()
  }

  private val immediateExecutionContext = ExecutionContext.fromExecutor(immediateExecutor)

  def apply[F[_]](datastaxSession: DatastaxSession)(implicit F: Async[F]): Session[F] =
    new Session[F] {
      final override def execute(query: String): F[ResultSet] =
        F.async_[ResultSet] { cb =>
          val future = datastaxSession.executeAsync(query)
          val runnable = new Runnable {
            override def run(): Unit =
              try cb(Right(future.get()))
              catch {
                case NonFatal(e) =>
                  cb(Left(e))
              }
          }
          future.addListener(runnable, immediateExecutor)
        }.evalOn(immediateExecutionContext)

      override def registerCodec[A](codec: TypeCodec[A]): F[Unit] =
        F.delay {
          datastaxSession.getCluster.getConfiguration.getCodecRegistry.register(codec)
          ()
        }
    }
}
