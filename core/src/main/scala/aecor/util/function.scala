package aecor.util

object function {
  def handle[A, B, Out](a: A, b: B)(f: A => B => Out): Out = f(a)(b)
  def handleF[A, B, F, Out](a: A, b: B)(f: A => F)(implicit H: FunctionBuilder[F, B, Out]): Out = H(f(a))(b)
}
