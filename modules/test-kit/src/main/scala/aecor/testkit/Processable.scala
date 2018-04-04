package aecor.testkit

import cats.Applicative

trait Processable[F[_], A] { outer =>
  def process(f: A => F[Unit]): F[Unit]
  def map[B](f: A => B): Processable[F, B] =
    new Processable[F, B] {
      override def process(f0: (B) => F[Unit]): F[Unit] = outer.process(a => f0(f(a)))
    }
  def merge(that: Processable[F, A])(implicit F: Applicative[F]): Processable[F, A] =
    new Processable[F, A] {
      override def process(f: (A) => F[Unit]): F[Unit] =
        F.map2(outer.process(f), that.process(f))((_, _) => ())
    }
}

object Processable {
  def empty[F[_]: Applicative, A]: Processable[F, A] = new Processable[F, A] {
    override def process(f: (A) => F[Unit]): F[Unit] = Applicative[F].pure(())
  }
}
