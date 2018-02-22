package aecor.macros
import aecor.encoding.WireProtocol.{Decoder, Encoder}
import Common._

import scala.annotation.compileTimeOnly
import scala.collection.immutable.Seq
import scala.meta._
import scala.util.Try

@compileTimeOnly("Cannot expand @wireProtocol")
class wireProtocol extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    Common.parseTraitAndCompanion(defn) match {
      case Some((t, c)) =>
        WireProtocolMacro(t, c)
      case None =>
        defn
    }

  }
}

object WireProtocolMacro {

  trait BoopickleUtil {
    def encoderFromPickler[A: boopickle.Default.Pickler]: Encoder[A] =
      Encoder.instance(a => boopickle.Default.Pickle.intoBytes(a))
    def decoderFromPickler[A: boopickle.Default.Pickler]: Decoder[A] =
      Decoder.fromTry(b => Try(boopickle.Default.Unpickle[A].fromBytes(b)))

    implicit def anyValPickler[A <: AnyVal, U](implicit A: shapeless.Unwrapped.Aux[A, U], U: boopickle.Pickler[U]): boopickle.Pickler[A] = boopickle.Default.transformPickler(A.wrap)(A.unwrap)
  }

  def apply(base: Defn.Trait, companion: Defn.Object): Term.Block = {
    val t = Common.Trait.fromDefn(base)

    val unifiedInvocation = t"({type X[A] = _root_.aecor.arrow.Invocation[${t.unified}, A]})#X"

    def toType(arg: Type.Arg): Type = arg match {
      case Type.Arg.Repeated(tpe) => tpe
      case Type.Arg.ByName(tpe) => tpe
      case tpe: Type => tpe
    }

    val generatedStats: Seq[Stat] = Seq(
      q"""
        implicit def aecorWireProtocol[..${t.params}]: _root_.aecor.encoding.WireProtocol[${t.unified}]
          with  _root_.io.aecor.liberator.FunctorK[${t.unified}]
           with _root_.aecor.ReifiedInvocations[${t.unified}] =
         new _root_.aecor.encoding.WireProtocol[${t.unified}] with  _root_.io.aecor.liberator.FunctorK[${t.unified}]
           with _root_.aecor.ReifiedInvocations[${t.unified}] with _root_.aecor.macros.WireProtocolMacro.BoopickleUtil {
            import boopickle.Default._

            final def mapK[F[_], G[_]](mf: ${t.name}[..${t.paramTypes}, F], fg: _root_.cats.arrow.FunctionK[F, G]): ${t.name}[..${t.paramTypes}, G] =
              new ${Ctor.Name(t.name.value)}[..${t.paramTypes}, G] {
                ..${
                  t.methods.map {
                    case m @ Method(name, tps, params, out) =>
                      if (params.nonEmpty)
                        q"final def $name[..$tps](..$params): G[$out] = fg(mf.$name(..${m.paramNames}))"
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
                      q"""final def $name[..$tps](..$params): _root_.aecor.arrow.Invocation[${t.unified}, $out] =
                         new _root_.aecor.arrow.Invocation[${t.unified}, $out] {
                           final def invoke[F[_]](mf: ${t.name}[..${t.paramTypes}, F]): F[$out] =
                             mf.$name(..${params.map(_.name.value).map(Term.Name(_))})
                         }
                       """
                    else
                      q"""final def $name[..$tps]: _root_.aecor.arrow.Invocation[${t.unified}, $out] =
                         new _root_.aecor.arrow.Invocation[${t.unified}, $out] {
                           final def invoke[F[_]](mf: ${t.name}[..${t.paramTypes}, F]): F[$out] =
                             mf.$name
                         }
                       """
                }
              }
            }


            final val encoder = new ${Ctor.Name(t.name.value)}[..${t.paramTypes}, _root_.aecor.encoding.WireProtocol.Encoded] {
              ..${
                t.methods.map {
                  case Method(name, tps, params, out) =>
                    if (params.nonEmpty) {
                      q"""
                        final def $name[..$tps](..$params): _root_.aecor.encoding.WireProtocol.Encoded[$out] = (
                          boopickle.Default.Pickle.intoBytes((${Lit.String(name.value)}, ..${params.map(_.name.value).map(Term.Name(_))})),
                          decoderFromPickler[$out]
                        )
                      """
                    } else {
                      q"""
                        final val ${Pat.Var.Term(name)}: _root_.aecor.encoding.WireProtocol.Encoded[$out] = (
                          boopickle.Default.Pickle.intoBytes(${Lit.String(name.value)}),
                          decoderFromPickler[$out]
                        )
                      """
                    }
                }
              }
            }

            final val decoder: _root_.aecor.encoding.WireProtocol.Decoder[aecor.data.PairE[$unifiedInvocation, _root_.aecor.encoding.WireProtocol.Encoder]] =
              new _root_.aecor.encoding.WireProtocol.Decoder[aecor.data.PairE[$unifiedInvocation, _root_.aecor.encoding.WireProtocol.Encoder]] {
                override def decode(bytes: java.nio.ByteBuffer): _root_.aecor.encoding.WireProtocol.Decoder.DecodingResult[aecor.data.PairE[$unifiedInvocation, _root_.aecor.encoding.WireProtocol.Encoder]] = {
                  val state = boopickle.UnpickleState(bytes)
                  state.unpickle[String] match {
                     ..case ${
                    val cases = t.methods.map {
                      case Method(name, tps, params, out) =>
                        if (params.nonEmpty) {
                          val arglist = (1 to params.size).map(i => s"_$i").map(x => q"args.${Term.Name(x)}")
                          val tupleTpeBase = Type.Name(s"Tuple${params.size}")
                          val nameLit = Lit.String(name.value)
                          val stats =
                            q"""
                            _root_.aecor.encoding.WireProtocol.Decoder.DecodingResult.fromTry(scala.util.Try {
                              val args = state.unpickle[$tupleTpeBase[..${params.map { case param"$n:${Some(tpe)}" => toType(tpe) }}]]
                              val invocation = new _root_.aecor.arrow.Invocation[${t.unified}, $out] {
                                final override def invoke[F[_]](mf: ${t.unified}[F]): F[$out] =
                                  mf.$name(..$arglist)
                                final override def toString = {
                                  val name = $nameLit
                                  s"$$name$$args"
                                }
                              }
                              _root_.aecor.data.PairE(invocation, encoderFromPickler[$out])
                            })
                        """
                          p"case $nameLit => $stats"
                        } else {
                          val stats =
                            q"""
                            _root_.aecor.encoding.WireProtocol.Decoder.DecodingResult.fromTry(scala.util.Try {
                              val invocation = new _root_.aecor.arrow.Invocation[${t.unified}, $out] {
                                final override def invoke[F[_]](mf: ${t.unified}[F]): F[$out] =
                                  mf.$name
                              }
                              _root_.aecor.data.PairE(invocation, encoderFromPickler[$out])
                            })
                        """
                          val nameLit = Lit.String(name.value)
                          p"case $nameLit => $stats"
                        }
                    }
                    cases :+ p"""case other => scala.util.Left(aecor.encoding.WireProtocol.Decoder.DecodingFailure(s"Unknown type tag $$other"))"""
                  }
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
