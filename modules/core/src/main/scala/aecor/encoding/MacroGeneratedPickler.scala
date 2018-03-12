package aecor.encoding

import boopickle.MaterializePicklerFallback

object MacroGeneratedPickler extends MaterializePicklerFallback {
  implicit def anyRefPickler[A <: AnyRef]: boopickle.Default.Pickler[A] = implicitly[boopickle.Default.Pickler[A]]
}
