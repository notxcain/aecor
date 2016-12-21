package aecor.util

import java.util.concurrent.Executor

import com.google.common.util.concurrent.ListenableFuture

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.Try

package object cassandra {
  implicit def listenableFutureToFuture[A](
    lf: ListenableFuture[A]
  )(implicit executionContext: ExecutionContext): Future[A] = {
    val promise = Promise[A]
    lf.addListener(
      new Runnable { def run() = { promise.complete(Try(lf.get())); () } },
      executionContext.asInstanceOf[Executor]
    )
    promise.future
  }
}
