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

    val unifiedInvocation = t"({type X[A] = aecor.encoding.WireProtocol.Invocation[$unifiedBase, A]})#X"

    val instanceName = Term.Name(s"aecorWireProtocol${typeName.value}")
    val companionStats: Seq[Stat] = Seq(
      q"""
        implicit def $instanceName[..$abstractParams]: aecor.encoding.WireProtocol[$unifiedBase]  =
         new aecor.encoding.WireProtocol[$unifiedBase] {

            final val encoder = new ${Ctor.Name(typeName.value)}[..$abstractTypes, aecor.encoding.WireProtocol.Encoded] {
              ..${
        traitStats.map {
          case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            q"""
                        final def $name[..$tps](..$params): aecor.encoding.WireProtocol.Encoded[$out] = (
                          _root_.scodec.bits.BitVector(_root_.boopickle.Default.Pickle.intoBytes((${Lit.String(name.value)}, ..${params.map(_.name.value).map(Term.Name(_))}))),
                          _root_.aecor.macros.boopickle.BoopickleCodec.decoder[$out]
                        )
                      """
          case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            q"""
                        final val ${Pat.Var.Term(name)}: aecor.encoding.WireProtocol.Encoded[$out] = (
                          _root_.scodec.bits.BitVector(_root_.boopickle.Default.Pickle.intoBytes(${Lit.String(name.value)})),
                          _root_.aecor.macros.boopickle.BoopickleCodec.decoder[$out]
                        )
                      """
          case other =>
            abort(s"Illegal method [$other]")
        }
      }
            }

            final val decoder: _root_.scodec.Decoder[aecor.data.PairE[$unifiedInvocation, _root_.scodec.Encoder]] =
              new _root_.scodec.Decoder[aecor.data.PairE[$unifiedInvocation, _root_.scodec.Encoder]] {

                final override def decode(bytes: _root_.scodec.bits.BitVector) = {
                  _root_.aecor.macros.boopickle.BoopickleCodec.attemptFromTry(scala.util.Try {
                  val state = _root_.boopickle.UnpickleState(bytes.toByteBuffer.order(_root_.java.nio.ByteOrder.LITTLE_ENDIAN))
                  state.unpickle[String] match {
                     ..case ${
        val cases = traitStats.map {
          case q"def $name[..$tps](..$params): ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            val arglist = (1 to params.size).map(i => s"_$i").map(x => q"args.${Term.Name(x)}")
            val tupleTpeBase = Type.Name(s"Tuple${params.size}")
            val nameLit = Lit.String(name.value)
            p"""case $nameLit =>
                  val args = state.unpickle[$tupleTpeBase[..${params.map { case param"$n:${Some(tpe)}" => toType(tpe) }}]]
                  val invocation = new aecor.encoding.WireProtocol.Invocation[$unifiedBase, $out] {
                    final override def run[F[_]](mf: $unifiedBase[F]): F[$out] =
                      mf.$name(..$arglist)
                    final override def toString: String = {
                      val name = $nameLit
                      s"$$name$$args"
                    }
                  }
                  aecor.data.PairE(invocation, _root_.aecor.macros.boopickle.BoopickleCodec.encoder[$out])
             """
          case q"def $name: ${someF: Type.Name}[$out]" if someF.value == theF.value =>
            val nameLit =  Lit.String(name.value)
            p"""case $nameLit =>
                  val invocation = new aecor.encoding.WireProtocol.Invocation[$unifiedBase, $out] {
                    final override def run[F[_]](mf: $unifiedBase[F]): F[$out] = mf.$name
                    final override def toString: String = $nameLit
                  }
                  aecor.data.PairE(invocation, _root_.aecor.macros.boopickle.BoopickleCodec.encoder[$out])
             """
          case other =>
            abort(s"Illegal method [$other]")
        }
        cases :+ p"""case other => throw new IllegalArgumentException(s"Unknown type tag $$other")"""
      }
                  }
                  })
                }
              }
          }
    """
    )

    val newCompanion = companion match {
      case Some(c) =>
        val oldTemplStats = c.templ.stats.getOrElse(Nil)
        c.copy(templ = c.templ.copy(stats = Some(oldTemplStats ++ companionStats)))
      case None =>
        q"object ${Term.Name(typeName.value)} { ..$companionStats }"

    }

    Term.Block(Seq(base, newCompanion))
  }
}
