package aecor.api

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import shapeless.{::, Generic, HList, HNil, Lazy}
import simulacrum.typeclass

@typeclass trait Router[A] {
  def route(a: A): Route
}

object Router {
  def route[A](a: A)(implicit e: Router[A]): Route = e.route(a)

  def instance[A](f: A => Route): Router[A] = new Router[A] {
    override def route(a: A): Route = f(a)
  }

  implicit val hnil: Router[HNil] = new Router[HNil] {
    override def route(a: (HNil)): Route = reject
  }

  implicit def hcons[H, T <: HList](implicit H: Lazy[Router[H]], T: Lazy[Router[T]]): Router[H :: T] = new Router[::[H, T]] {
    override def route(a: (::[H, T])): Route = {
      H.value.route(a.head) ~ T.value.route(a.tail)
    }
  }

  implicit def generic[A, Repr](implicit gen: Generic.Aux[A, Repr], Repr: Router[Repr]): Router[A] = new Router[A] {
    override def route(a: A): Route = Repr.route(gen.to(a))
  }

  implicit def routeRouter: Router[Route] = new Router[Route] {
    override def route(a: Route): Route = a
  }
}
