package aecor.macros

import scala.collection.immutable.Seq
import scala.meta._


class wireProtocol extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val commonFields = this match {
      case q"new $_(..$xs)" => xs.map { case ctor"$_(${Lit(x: String)})" => x }.toList
      case _ => Nil
    }
    defn match {
      case Term.Block(Seq(t: Defn.Trait, companion: Defn.Object)) =>
        WireProtocolMacro(commonFields, t, Some(companion))
      case t: Defn.Trait =>
        WireProtocolMacro(commonFields, t, None)
      case other =>
        defn
    }
  }
}

object WireProtocolMacro {

  def apply(commonFields: List[String], base: Defn.Trait, companion: Option[Defn.Object]): Term.Block = {
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

    def toType(arg: Type.Arg): Type = arg match {
      case Type.Arg.Repeated(tpe) => tpe
      case Type.Arg.ByName(tpe) => tpe
      case tpe: Type => tpe
    }

    val unifiedInvocation = t"({type X[A] = aecor.arrow.Invocation[$unifiedBase, A]})#X"

    val companionStats: Seq[Stat] = Seq(
      q"""
        implicit def aecorWireProtocol[..$abstractParams]: aecor.gadt.WireProtocol[$unifiedBase] =
         new aecor.gadt.WireProtocol[$unifiedBase] {
            import boopickle.Default._

            implicit def anyValPickler[A <: AnyVal, U](implicit A: shapeless.Unwrapped.Aux[A, U], U: boopickle.Pickler[U]): boopickle.Pickler[A] =
               boopickle.Default.transformPickler(A.wrap)(A.unwrap)

            final def mapK[F[_], G[_]](mf: $typeName[..$abstractTypes, F], fg: _root_.cats.arrow.FunctionK[F, G]): $typeName[..$abstractTypes, G] =
              new ${Ctor.Name(typeName.value)}[..$abstractTypes, G] {
                ..${
                  traitStats.map {
                    case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
                      q"final def $name[..$tps](..$params): G[$out] = fg(mf.$name(..${params.map(_.name.value).map(Term.Name(_))}))"
                    case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
                      q"final def $name: G[$out] = fg(mf.$name)"
                    case other =>
                      abort(s"Illegal method [$other]")
                  }
                }
              }

            final val encoder = new ${Ctor.Name(typeName.value)}[..$abstractTypes, aecor.gadt.WireProtocol.Encoded] {
              ..${
                traitStats.map {
                  case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
                    q"""
                        final def $name[..$tps](..$params): aecor.gadt.WireProtocol.Encoded[$out] = (
                          boopickle.Default.Pickle.intoBytes((${Lit.String(name.value)}, ..${params.map(_.name.value).map(Term.Name(_))})),
                          aecor.gadt.WireProtocol.Decoder.fromPickler[$out]
                        )
                      """
                  case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
                    q"""
                        final val ${Pat.Var.Term(name)}: aecor.gadt.WireProtocol.Encoded[$out] = (
                          boopickle.Default.Pickle.intoBytes(${Lit.String(name.value)}),
                          aecor.gadt.WireProtocol.Decoder.fromPickler[$out]
                        )
                      """
                  case other =>
                    abort(s"Illegal method [$other]")
                }
              }
            }

            final val decoder: aecor.gadt.WireProtocol.Decoder[aecor.gadt.PairE[$unifiedInvocation, aecor.gadt.WireProtocol.Encoder]] =
              new aecor.gadt.WireProtocol.Decoder[aecor.gadt.PairE[$unifiedInvocation, aecor.gadt.WireProtocol.Encoder]] {
                override def decode(bytes: java.nio.ByteBuffer): aecor.gadt.WireProtocol.Decoder.DecodingResult[aecor.gadt.PairE[$unifiedInvocation, aecor.gadt.WireProtocol.Encoder]] = {
                  val state = boopickle.UnpickleState(bytes)
                  state.unpickle[String] match {
                     ..case ${
                    val cases = traitStats.map {
                      case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
                        val arglist = (1 to params.size).map(i => s"_$i").map(x => q"args.${Term.Name(x)}")
                        val tupleTpeBase = Type.Name(s"Tuple${params.size}")
                        val nameLit = Lit.String(name.value)
                        val stats = q"""
                            aecor.gadt.WireProtocol.Decoder.DecodingResult.fromTry(scala.util.Try {
                              val args = state.unpickle[$tupleTpeBase[..${params.map { case param"$n:${Some(tpe)}" => toType(tpe) }}]]
                              val invocation = new aecor.arrow.Invocation[$unifiedBase, $out] {
                                final override def invoke[F[_]](mf: $unifiedBase[F]): F[$out] =
                                  mf.$name(..$arglist)
                                final override def toString = {
                                  val name = $nameLit
                                  s"$$name$$args"
                                }
                              }
                              aecor.gadt.PairE(invocation, aecor.gadt.WireProtocol.Encoder.fromPickler[$out])
                            })
                        """
                        p"case $nameLit => $stats"
                      case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
                        val stats = q"""
                            aecor.gadt.WireProtocol.Decoder.DecodingResult.fromTry(scala.util.Try {
                              val invocation = new aecor.arrow.Invocation[$unifiedBase, $out] {
                                final override def invoke[F[_]](mf: $unifiedBase[F]): F[$out] =
                                  mf.$name
                              }
                              aecor.gadt.PairE(invocation, aecor.gadt.WireProtocol.Encoder.fromPickler[$out])
                            })
                        """
                        val nameLit = Lit.String(name.value)
                        p"case $nameLit => $stats"
                      case other =>
                        abort(s"Illegal method [$other]")
                    }
                    cases :+ p"""case other => scala.util.Left(aecor.gadt.WireProtocol.Decoder.DecodingFailure(s"Unknown type tag $$other"))"""
                  }
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
