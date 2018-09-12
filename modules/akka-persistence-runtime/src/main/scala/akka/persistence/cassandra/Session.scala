package akka.persistence.cassandra
import java.util.concurrent.Executor

import cats.effect.Async
import com.datastax.driver.core.{ResultSet, TypeCodec, Session => DatastaxSession}

import scala.util.control.NonFatal

trait Session[F[_]] {
  def execute(query: String): F[ResultSet]
  def registerCodec[A](codec: TypeCodec[A]): F[Unit]
}

object Session {
  private val immediateExecutor = new Executor {
    override def execute(command: Runnable): Unit =
      command.run()
  }

  def apply[F[_]](datastaxSession: DatastaxSession)(implicit F: Async[F]): Session[F] =
    new Session[F] {
      final override def execute(query: String): F[ResultSet] =
        F.async { cb =>
          val future = datastaxSession.executeAsync(query)
          val runnable = new Runnable {
            override def run(): Unit =
              try {
                cb(Right(future.get()))
              } catch {
                case NonFatal(e) =>
                  cb(Left(e))
              }
          }
          future.addListener(runnable, immediateExecutor)
        }
      override def registerCodec[A](codec: TypeCodec[A]): F[Unit] =
        F.delay {
          datastaxSession.getCluster.getConfiguration.getCodecRegistry.register(codec)
          ()
        }
    }
}
