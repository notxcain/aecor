package aecor.streaming

import scala.concurrent.Future

final case class Committable[+A](commit: () => Future[Unit], value: A) {
  def map[B](f: A => B): Committable[B] = copy(value = f(value))
}

object Committable {
  implicit def commitInstance[Offset]: Commit[Committable[Offset]] =
    new Commit[Committable[Offset]] {
      override def commit(a: Committable[Offset]): Future[Unit] = a.commit()
    }
}

trait Commit[A] {
  def commit(a: A): Future[Unit]
}
