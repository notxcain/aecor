package aecor.core.process

import aecor.util.FunctionBuilderSyntax

trait ProcessSyntax extends FunctionBuilderSyntax {
  final def when[A] = at[A]
}

object ProcessSyntax extends ProcessSyntax
