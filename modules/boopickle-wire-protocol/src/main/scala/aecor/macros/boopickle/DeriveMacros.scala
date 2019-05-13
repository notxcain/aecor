package aecor.macros.boopickle

import aecor.encoding.WireProtocol
import aecor.encoding.WireProtocol.{ Encoded, Invocation }

import scala.reflect.macros.blackbox

class DeriveMacros(val c: blackbox.Context) {
  import c.internal._
  import c.universe._

  /** A reified method definition with some useful methods for transforming it. */
  case class Method(m: MethodSymbol,
                    tps: List[TypeDef],
                    pss: List[List[ValDef]],
                    rt: Type,
                    body: Tree) {
    def typeArgs: List[Type] = for (tp <- tps) yield typeRef(NoPrefix, tp.symbol, Nil)
    def paramLists(f: Type => Type): List[List[ValDef]] =
      for (ps <- pss)
        yield for (p <- ps) yield ValDef(p.mods, p.name, TypeTree(f(p.tpt.tpe)), p.rhs)
    def argLists(f: (TermName, Type) => Tree): List[List[Tree]] =
      for (ps <- pss)
        yield for (p <- ps) yield f(p.name, p.tpt.tpe)
    def definition: Tree = q"override def ${m.name}[..$tps](...$pss): $rt = $body"

  }

  /** Return the set of overridable members of `tpe`, excluding some undesired cases. */
  // TODO: Figure out what to do about different visibility modifiers.
  def overridableMembersOf(tpe: Type): Iterable[Symbol] = {
    import definitions._
    val exclude = Set[Symbol](AnyClass, AnyRefClass, AnyValClass, ObjectClass)
    tpe.members.filterNot(
      m =>
        m.isConstructor || m.isFinal || m.isImplementationArtifact || m.isSynthetic || exclude(
          m.owner
      )
    )
  }

  /** Temporarily refresh type parameter names, type-check the `tree` and restore the original names.
    *
    * The purpose is to avoid warnings about type parameter shadowing, which can be problematic when
    * `-Xfatal-warnings` is enabled. We know the warnings are harmless because we deal with types directly.
    * Unfortunately `c.typecheck(tree, silent = true)` does not suppress warnings.
    */
  def typeCheckWithFreshTypeParams(tree: Tree): Tree = {
    val typeParams = tree.collect {
      case method: DefDef => method.tparams.map(_.symbol)
    }.flatten

    val originalNames = for (tp <- typeParams) yield {
      val original = tp.name.toTypeName
      if (tp != NoSymbol)
        setName(tp, TypeName(c.freshName(original.toString)))
      original
    }

    val typed = c.typecheck(tree)
    for ((tp, original) <- typeParams zip originalNames) if (tp != NoSymbol) setName(tp, original)
    typed
  }

  /** Delegate the definition of type members and aliases in `algebra`. */
  def delegateTypes(algebra: Type, members: Iterable[Symbol])(
    rhs: (TypeSymbol, List[Type]) => Type
  ): Iterable[Tree] =
    for (member <- members if member.isType) yield {
      val tpe = member.asType
      val signature = tpe.typeSignatureIn(algebra)
      val typeParams = for (t <- signature.typeParams) yield typeDef(t)
      val typeArgs = for (t <- signature.typeParams) yield typeRef(NoPrefix, t, Nil)
      q"type ${tpe.name}[..$typeParams] = ${rhs(tpe, typeArgs)}"
    }

  def overridableMethodsOf(algebra: Type): Iterable[Method] =
    for (member <- overridableMembersOf(algebra) if member.isMethod && !member.asMethod.isAccessor)
      yield {
        val method = member.asMethod
        val signature = method.typeSignatureIn(algebra)
        val typeParams = for (tp <- signature.typeParams) yield typeDef(tp)
        val paramLists = for (ps <- signature.paramLists)
          yield
            for (p <- ps) yield {
              // Only preserve the implicit modifier (e.g. drop the default parameter flag).
              val modifiers = if (p.isImplicit) Modifiers(Flag.IMPLICIT) else Modifiers()
              ValDef(modifiers, p.name.toTermName, TypeTree(p.typeSignatureIn(algebra)), EmptyTree)
            }

        Method(
          method,
          typeParams,
          paramLists,
          signature.finalResultType,
          q"_root_.scala.Predef.???"
        )
      }

  /** Type-check a definition of type `instance` with stubbed methods to gain more type information. */
  def declare(instance: Type): Tree = {
    val stubs =
      overridableMethodsOf(instance).map(_.definition)

    val Block(List(declaration), _) = typeCheckWithFreshTypeParams(q"new $instance { ..$stubs }")
    declaration
  }

  /** Implement a possibly refined `algebra` with the provided `members`. */
  def implement(algebra: Type, members: Iterable[Tree]): Tree = {
    // If `members.isEmpty` we need an extra statement to ensure the generation of an anonymous class.
    val nonEmptyMembers = if (members.isEmpty) q"()" :: Nil else members

    algebra match {
      case RefinedType(parents, scope) =>
        val refinements = delegateTypes(algebra, scope.filterNot(_.isAbstract)) { (tpe, _) =>
          tpe.typeSignatureIn(algebra).resultType
        }

        q"new ..$parents { ..$refinements; ..$nonEmptyMembers }"
      case _ =>
        q"new $algebra { ..$nonEmptyMembers }"
    }
  }

  /** Create a new instance of `typeClass` for `algebra`.
    * `rhs` should define a mapping for each method (by name) to an implementation function based on type signature.
    */
  def instantiate(typeClass: TypeSymbol, params: Type*)(rhs: (String, Type => Tree)*): Tree = {
    val impl = rhs.toMap
    val TcA = appliedType(typeClass, params: _*)
    val declaration @ ClassDef(_, _, _, Template(parents, self, members)) = declare(TcA)
    val implementations = for (member <- members)
      yield
        member match {
          case dd: DefDef =>
            val method = member.symbol.asMethod
            impl
              .get(method.name.toString)
              .fold(dd)(f => defDef(method, f(method.typeSignatureIn(TcA))))
          case other => other
        }

    val definition =
      classDef(declaration.symbol, Template(parents, self, implementations))
    typeCheckWithFreshTypeParams(q"{ $definition; new ${declaration.symbol} }")
  }

  def encoder(algebra: Type): (String, Type => Tree) =
    "encoder" -> { _ =>
      val methods = overridableMethodsOf(algebra).map {
        case method @ Method(name, _, _, TypeRef(_, _, outParams), _) =>
          val args = method.argLists((pn, _) => Ident(pn)).flatten
          val body =
            q"""(_root_.scodec.bits.BitVector(_root_.boopickle.Default.Pickle.intoBytes((${name.name.toString}, ..$args)))
                ,_root_.aecor.macros.boopickle.BoopickleCodec.decoder[${outParams.last}]
                )"""

          method
            .copy(rt = appliedType(symbolOf[Encoded[Any]].toType, outParams.last), body = body)
            .definition
      }
      implement(appliedType(algebra, symbolOf[Encoded[Any]].toTypeConstructor), methods)
    }

  def decoder(algebra: Type): (String, Type => Tree) =
    "decoder" -> { t =>
      val ifs =
        overridableMethodsOf(algebra)
          .foldLeft(q"""throw new IllegalArgumentException(s"Unknown type tag $$hint")""": Tree) {
            case (acc, Method(name, _, pss, TypeRef(_, _, outParams), _)) =>
              val out = outParams.last
              val argList = pss.map(x => (1 to x.size).map(i => q"args.${TermName(s"_$i")}"))

              val Invocation = appliedType(symbolOf[Invocation[Any, Any]], algebra, out)

              def runImplementation(instance: TermName) =
                if (argList.isEmpty)
                  q"$instance.$name"
                else
                  q"$instance.$name(...$argList)"

              val toStringImpl = q"""
               override def toString: String = {
                 val name = ${name.name.toString}
                 val algebraName = ${algebra.typeSymbol.name.toString}
                ${if (argList.isEmpty) q"""s"$$algebraName#$$name""""
              else q"""s"$$algebraName#$$name$$args""""}
              }"""

              val members = toStringImpl :: overridableMethodsOf(Invocation).map {
                case m @ Method(_, _, List(List(ValDef(_, ps, _, _))), _, _) =>
                  m.copy(body = runImplementation(ps)).definition
              }.toList

              val invocation = implement(Invocation, members)

              val argsTerm =
                if (argList.isEmpty) q""
                else {
                  val paramTypes = pss.flatten.map(_.tpt)
                  val TupleNCons = TypeName(s"Tuple${paramTypes.size}")
                  q"val args = state.unpickle[$TupleNCons[..$paramTypes]]"
                }

              val pair =
                q"""
                    $argsTerm
                    val invocation = $invocation
                    val resultEncoder = _root_.aecor.macros.boopickle.BoopickleCodec.encoder[$out]
                    _root_.aecor.data.PairE(invocation, resultEncoder)
                  """

              q"if (hint == ${name.name.toString}) $pair else $acc"
          }

      val out = q"""
           new ${t.finalResultType} {
             final override def decode(bytes: _root_.scodec.bits.BitVector) =
              _root_.aecor.macros.boopickle.BoopickleCodec.attemptFromTry(scala.util.Try {
                val state = _root_.boopickle.UnpickleState(bytes.toByteBuffer.order(_root_.java.nio.ByteOrder.LITTLE_ENDIAN))
                val hint = state.unpickle[String]
                $ifs
             })
          }
         """
      out
    }

  def derive[Alg[_[_]]](implicit tag: c.WeakTypeTag[Alg[Any]]): c.Tree = {
    val Alg = tag.tpe.typeConstructor.dealias
    instantiate(symbolOf[WireProtocol[Any]], Alg)(encoder(Alg), decoder(Alg))
  }

}
