package aecor.macros

import scala.annotation.compileTimeOnly
import scala.collection.immutable.Seq
import scala.meta._
import Common._

@compileTimeOnly("Cannot expand @reifyInvocations")
class reifyInvocations extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    Common.parseTraitAndCompanion(defn) match {
      case Some((t, c)) =>
        ReifiedInvocationsMacro(t, c)
      case None =>
        defn
    }
  }
}

object ReifiedInvocationsMacro {

  def apply(base: Defn.Trait, companion: Defn.Object): Term.Block = {
    val t = Common.Trait.fromDefn(base)

    val unifiedInvocation = t"({type X[A] = aecor.arrow.Invocation[${t.unified}, A]})#X"

    val generatedStats: Seq[Stat] = Seq(
      q"""
        implicit def aecorReifiedInvocation[..${t.params}]: aecor.ReifiedInvocation[${t.unified}] =
          new aecor.ReifiedInvocation[${t.unified}] {
            final def mapK[F[_], G[_]](mf: ${t.name}[..${t.paramTypes}, F], fg: _root_.cats.arrow.FunctionK[F, G]): ${t.name}[..${t.paramTypes}, G] =
              new ${Ctor.Name(t.name.value)}[..${t.paramTypes}, G] {
                ..${
                  t.methods.map {
                    case Method(name, tps, params, out) =>
                      if (params.nonEmpty)
                        q"final def $name[..$tps](..$params): G[$out] = fg(mf.$name(..${params.map(_.name.value).map(Term.Name(_))}))"
                      else
                        q"final def $name[..$tps]: G[$out] = fg(mf.$name)"
                  }
                }
              }

            final val instance: ${t.name}[..${t.paramTypes}, $unifiedInvocation] = new ${Ctor.Name(t.name.value)}[..${t.paramTypes}, $unifiedInvocation] {
              ..${
                t.methods.map {
                  case Method(name, tps, params, out) =>
                    if (params.nonEmpty)
                      q"""final def $name[..$tps](..$params): aecor.arrow.Invocation[${t.unified}, $out] =
                         new aecor.arrow.Invocation[${t.unified}, $out] {
                           final def invoke[F[_]](mf: ${t.name}[..${t.paramTypes}, F]): F[$out] =
                             mf.$name(..${params.map(_.name.value).map(Term.Name(_))})
                         }
                       """
                    else
                      q"""final def $name[..$tps]: aecor.arrow.Invocation[${t.unified}, $out] =
                         new aecor.arrow.Invocation[${t.unified}, $out] {
                           final def invoke[F[_]](mf: ${t.name}[..${t.paramTypes}, F]): F[$out] =
                             mf.$name
                         }
                       """
                }
              }
            }

          }
    """
    )

    val newCompanion = {
      val currentStats = companion.templ.stats.getOrElse(Nil)
      companion.copy(templ = companion.templ.copy(stats = Some(currentStats ++ generatedStats)))
    }

    Term.Block(Seq(base, newCompanion))
  }
}