package aecor.util

import shapeless.{ :+:, ::, CNil, Coproduct, Generic, HList, HNil }

trait FunctionBuilder[H, Input, +Out] {
  def apply(f: H): Input => Out
}

object FunctionBuilder extends FunctionBuilderInstances with LowerFunctionBuilderInstances {

  object syntax extends FunctionBuilderSyntax

  trait Apply[In] {
    def apply[H, Out](f: H)(implicit ev: FunctionBuilder[H, In, Out]): In => Out
  }

  def apply[In] = new Apply[In] {
    override def apply[H, Out](f: H)(implicit ev: FunctionBuilder[H, In, Out]): In => Out =
      ev(f)
  }
}

trait FunctionBuilderInstances {
  implicit def hNil[Out]: FunctionBuilder[HNil, CNil, Out] =
    new FunctionBuilder[HNil, CNil, Out] {
      override def apply(handler: HNil): (CNil) => Out =
        _.impossible
    }

  implicit def hCons[A, HT <: HList, IT <: Coproduct, Out](
    implicit tailBuilder: FunctionBuilder[HT, IT, Out]
  ): FunctionBuilder[(A => Out) :: HT, A :+: IT, Out] =
    new FunctionBuilder[(A => Out) :: HT, A :+: IT, Out] {
      def apply(handlers: (A => Out) :: HT): A :+: IT => Out =
        _.eliminate(handlers.head, tailBuilder(handlers.tail))
    }
}

trait LowerFunctionBuilderInstances {
  implicit def function[A, B]: FunctionBuilder[A => B, A, B] = new FunctionBuilder[A => B, A, B] {
    override def apply(handlers: (A) => B): (A) => B = handlers
  }
  implicit def genBuilder[A, Repr, In, Out](
    implicit gen: Generic.Aux[A, Repr],
    fromRepr: FunctionBuilder[Repr, In, Out]
  ): FunctionBuilder[A, In, Out] =
    new FunctionBuilder[A, In, Out] {
      override def apply(f: A): (In) => Out =
        fromRepr(gen.to(f))
    }
}

trait FunctionBuilderSyntax {
  trait At[A] {
    def apply[Out](f: A => Out): A => Out
  }

  final def at[A]: At[A] = new At[A] {
    override def apply[Out](f: (A) => Out): (A) => Out = f
  }

  trait Build[In] {
    def apply[H, Out](f: H)(implicit ev: FunctionBuilder[H, In, Out]): In => Out
  }

  def build[In] = new Build[In] {
    override def apply[H, Out](f: H)(implicit ev: FunctionBuilder[H, In, Out]): In => Out =
      ev(f)
  }
}
