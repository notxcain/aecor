package aecor.macros.boopickle
import aecor.encoding.WireProtocol

object BoopickleWireProtocol {
  def derive[M[_[_]]]: WireProtocol[M] = macro DeriveMacros.derive[M]
}
