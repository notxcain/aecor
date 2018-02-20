package aecor.macros

import scala.collection.immutable.Seq
import scala.meta._

class reifyInvocations extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    defn match {
      case Term.Block(Seq(t: Defn.Trait, companion: Defn.Object)) =>
        FunctorKMacro(t, Some(companion))
      case t: Defn.Trait =>
        FunctorKMacro(t, None)
      case other =>
        defn
    }
  }
}

object FunctorKMacro {

  final case class Method(name: Term.Name, typeParams: Seq[Type.Param], params: Seq[Term.Param], out: Type)

  def apply(base: Defn.Trait, companion: Option[Defn.Object]): Term.Block = {
    val typeName = base.name
    val traitStats = base.templ.stats.get
    val (theF, abstractParams) = (base.tparams.last.name, base.tparams.dropRight(1))
    val abstractTypes = abstractParams.map(_.name.value).map(Type.Name(_))

    val unifiedBase =
      if (abstractTypes.isEmpty) {
        base.name
      } else {
        t"({type X[F[_]] = $typeName[..$abstractTypes, F]})#X"
      }

    val methods = traitStats.map {
      case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
        Method(name, tps, params, out)
      case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
        Method(name, Seq.empty, Seq.empty, out)
      case other =>
        abort(s"Illegal method [$other]")
    }

    val unifiedInvocation = t"({type X[A] = aecor.arrow.Invocation[$unifiedBase, A]})#X"

    val companionStats: Seq[Stat] = Seq(
      q"""
        implicit def aecorReifiedInvocation[..$abstractParams]: aecor.ReifiedInvocation[$unifiedBase] =
          new aecor.ReifiedInvocation[$unifiedBase] {
            final def mapK[F[_], G[_]](mf: $typeName[..$abstractTypes, F], fg: _root_.cats.arrow.FunctionK[F, G]): $typeName[..$abstractTypes, G] =
              new ${Ctor.Name(typeName.value)}[..$abstractTypes, G] {
                ..${
                  methods.map {
                    case Method(name, tps, params, out) =>
                      if (params.nonEmpty)
                        q"final def $name[..$tps](..$params): G[$out] = fg(mf.$name(..${params.map(_.name.value).map(Term.Name(_))}))"
                      else
                        q"final def $name[..$tps]: G[$out] = fg(mf.$name)"
                  }
                }
              }

            final val instance: $typeName[..$abstractTypes, $unifiedInvocation] = new ${Ctor.Name(typeName.value)}[..$abstractTypes, $unifiedInvocation] {
              ..${
                methods.map {
                  case Method(name, tps, params, out) =>
                    if (params.nonEmpty)
                      q"""final def $name[..$tps](..$params): aecor.arrow.Invocation[$unifiedBase, $out] =
                         new aecor.arrow.Invocation[$unifiedBase, $out] {
                           final def invoke[F[_]](mf: $typeName[..$abstractTypes, F]): F[$out] =
                             mf.$name(..${params.map(_.name.value).map(Term.Name(_))})
                         }
                       """
                    else
                      q"""final def $name[..$tps]: aecor.arrow.Invocation[$unifiedBase, $out] =
                         new aecor.arrow.Invocation[$unifiedBase, $out] {
                           final def invoke[F[_]](mf: $typeName[..$abstractTypes, F]): F[$out] =
                             mf.$name
                         }
                       """
                }
              }
            }

          }
    """
    )

    val newCompanion = companion match {
      case Some(c) =>
        val oldTemplStats = c.templ.stats.getOrElse(Nil)
        c.copy(templ = c.templ.copy(stats = Some(companionStats ++ oldTemplStats)))
      case None =>
        q"object ${Term.Name(typeName.value)} { ..$companionStats }"

    }

    Term.Block(Seq(base, newCompanion))
  }
}