package aecor.example

import akka.http.scaladsl.marshalling.{Marshaller, ToResponseMarshallable, ToResponseMarshaller}
import cats.effect.Effect

trait EffectSupport {
  implicit def effectToResponseMarshallable[F[_], A](
    fa: F[A]
  )(implicit A: ToResponseMarshaller[A], F: Effect[F]): ToResponseMarshallable =
    new ToResponseMarshallable {
      override implicit def marshaller: ToResponseMarshaller[F[A]] =
        Marshaller { implicit ec => fa =>
        F.toIO(fa).unsafeToFuture().flatMap(A(_))
        }

      override def value: F[A] = fa

      override type T = F[A]
    }
}

object EffectSupport extends EffectSupport
