package aecor.experimental

case class Identified[I, A](identity: I, a: A)
object Identified {
  type Aux[I, O[_], A] = Identified[I, O[A]]
}
