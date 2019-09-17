package aecor.macros.boopickle
import aecor.encoding.WireProtocol

object BoopickleWireProtocol {
  @SuppressWarnings(Array("org.wartremover.warts.All"))
  def derive[M[_[_]]]: WireProtocol[M] = macro DeriveMacros.derive[M]
}
