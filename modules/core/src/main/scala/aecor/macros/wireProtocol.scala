package aecor.macros

import scala.annotation.compileTimeOnly
import scala.collection.immutable.Seq
import scala.meta._


@compileTimeOnly("Can not expand boopickleWireProtocol")
class boopickleWireProtocol extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val commonFields = this match {
      case q"new $_(..$xs)" => xs.map { case ctor"$_(${Lit(x: String)})" => x }.toList
      case _ => Nil
    }
    defn match {
      case Term.Block(Seq(t: Defn.Trait, companion: Defn.Object)) =>
        BoopickleWireProtocolMacro(commonFields, t, Some(companion))
      case t: Defn.Trait =>
        BoopickleWireProtocolMacro(commonFields, t, None)
      case other =>
        defn
    }
  }
}

object BoopickleWireProtocolMacro {

  final case class Method(name: Term.Name, typeParams: Seq[Type.Param], params: Seq[Term.Param], out: Type)

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
        implicit def aecorWireProtocol[..$abstractParams]: aecor.encoding.WireProtocol[$unifiedBase] with _root_.io.aecor.liberator.FunctorK[$unifiedBase] with _root_.aecor.ReifiedInvocation[$unifiedBase]  =
         new aecor.encoding.WireProtocol[$unifiedBase] with _root_.io.aecor.liberator.FunctorK[$unifiedBase] with _root_.aecor.ReifiedInvocation[$unifiedBase]{
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

            import _root_.boopickle.Default._

            final val encoder = new ${Ctor.Name(typeName.value)}[..$abstractTypes, aecor.encoding.WireProtocol.Encoded] {
              ..${
        traitStats.map {
          case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            q"""
                        final def $name[..$tps](..$params): aecor.encoding.WireProtocol.Encoded[$out] = (
                          Pickle.intoBytes((${Lit.String(name.value)}, ..${params.map(_.name.value).map(Term.Name(_))})),
                          _root_.aecor.encoding.WireProtocol.Decoder.fromTry(bs => _root_.scala.util.Try(Unpickle[$out].fromBytes(bs)))
                        )
                      """
          case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            q"""
                        final val ${Pat.Var.Term(name)}: aecor.encoding.WireProtocol.Encoded[$out] = (
                          Pickle.intoBytes(${Lit.String(name.value)}),
                          aecor.encoding.WireProtocol.Decoder.fromTry(bs => _root_.scala.util.Try(Unpickle[$out].fromBytes(bs)))
                        )
                      """
          case other =>
            abort(s"Illegal method [$other]")
        }
      }
            }

            final val decoder: _root_.aecor.encoding.WireProtocol.Decoder[aecor.data.PairE[$unifiedInvocation, aecor.encoding.WireProtocol.Encoder]] =
              new _root_.aecor.encoding.WireProtocol.Decoder[aecor.data.PairE[$unifiedInvocation, aecor.encoding.WireProtocol.Encoder]] {
                override def decode(bytes: _root_.java.nio.ByteBuffer): _root_.aecor.encoding.WireProtocol.Decoder.DecodingResult[aecor.data.PairE[$unifiedInvocation, aecor.encoding.WireProtocol.Encoder]] = {
                  val state = _root_.boopickle.UnpickleState(bytes)
                  state.unpickle[String] match {
                     ..case ${
        val cases = traitStats.map {
          case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            val arglist = (1 to params.size).map(i => s"_$i").map(x => q"args.${Term.Name(x)}")
            val tupleTpeBase = Type.Name(s"Tuple${params.size}")
            val nameLit = Lit.String(name.value)
            val stats = q"""
                            aecor.encoding.WireProtocol.Decoder.DecodingResult.fromTry(scala.util.Try {
                              val args = state.unpickle[$tupleTpeBase[..${params.map { case param"$n:${Some(tpe)}" => toType(tpe) }}]]
                              val invocation = new aecor.arrow.Invocation[$unifiedBase, $out] {
                                final override def invoke[F[_]](mf: $unifiedBase[F]): F[$out] =
                                  mf.$name(..$arglist)
                                final override def toString = {
                                  val name = $nameLit
                                  s"$$name$$args"
                                }
                              }
                              aecor.data.PairE(invocation, aecor.encoding.WireProtocol.Encoder.instance[$out](Pickle.intoBytes))
                            })
                        """
            p"case $nameLit => $stats"
          case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            val stats = q"""
                            aecor.encoding.WireProtocol.Decoder.DecodingResult.fromTry(scala.util.Try {
                              val invocation = new aecor.arrow.Invocation[$unifiedBase, $out] {
                                final override def invoke[F[_]](mf: $unifiedBase[F]): F[$out] =
                                  mf.$name
                              }
                              aecor.data.PairE(invocation, aecor.encoding.WireProtocol.Encoder.instance[$out](Pickle.intoBytes))
                            })
                        """
            val nameLit = Lit.String(name.value)
            p"case $nameLit => $stats"
          case other =>
            abort(s"Illegal method [$other]")
        }
        cases :+ p"""case other => scala.util.Left(aecor.encoding.WireProtocol.Decoder.DecodingFailure(s"Unknown type tag $$other"))"""
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