package aecor.example

import akka.http.scaladsl.marshalling.{ Marshaller, ToResponseMarshallable, ToResponseMarshaller }
import monix.eval.Task
import monix.execution.Scheduler
trait MonixSupport {
  implicit def taskToResponseMarshallable[A](
    task: Task[A]
  )(implicit A: ToResponseMarshaller[A]): ToResponseMarshallable =
    new ToResponseMarshallable {
      override implicit def marshaller: ToResponseMarshaller[Task[A]] =
        Marshaller { implicit ec => task =>
          task.runAsync(Scheduler(ec)).flatMap(A(_))
        }

      override def value: Task[A] = task

      override type T = Task[A]
    }
}

object MonixSupport extends MonixSupport
