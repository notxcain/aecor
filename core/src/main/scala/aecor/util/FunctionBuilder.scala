package aecor.util

import shapeless.{:+:, ::, CNil, Coproduct, HList, HNil, Inl, Inr}

trait FunctionBuilder[H, Input, +Out] {
  def apply(handlers: H): Input => Out
}

object FunctionBuilder {
  type Handler[T, Out] = T => Out

  private val syntax = new FunctionBuilderSyntax {}

  trait Apply[In] {
    def apply[H, Out](f: H)(implicit ev: FunctionBuilder[H, In, Out]): In => Out
  }
  def apply[In] = new Apply[In] {
    override def apply[H, Out](f: H)(implicit ev: FunctionBuilder[H, In, Out]): In => Out =
      ev(f)
  }

  implicit def hNil[Out]: FunctionBuilder[HNil, CNil, Out] = new FunctionBuilder[HNil, CNil, Out] {
    override def apply(handler: HNil): (CNil) => Out =
      x => x.impossible
  }
  implicit def hCons
  [A, HT <: HList, IT <: Coproduct, Out]
  (implicit tailBuilder: FunctionBuilder[HT, IT, Out]): FunctionBuilder[(A => Out) :: HT, A :+: IT, Out] =
    new FunctionBuilder[(A => Out) :: HT, A :+: IT, Out] {
      def apply(handlers: (A => Out) :: HT): A :+: IT => Out = {
        case Inl(a) => handlers.head(a)
        case Inr(r) => tailBuilder(handlers.tail)(r)
      }
    }
}

trait FunctionBuilderSyntax {
  trait At[A] {
    def apply[Out](f: A => Out): A => Out
  }
  final def at[A] = new At[A] {
    override def apply[Out](f: (A) => Out): (A) => Out = f
  }
  final def `if`[A, Out](a: A)(o: Out): A => Out = _ => o
}
