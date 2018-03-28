package aecor.macros
import scala.meta._
import scala.collection.immutable._

object Common {
  final case class Method(name: Term.Name,
                          typeParams: Seq[Type.Param],
                          params: Seq[Term.Param],
                          out: Type) {
    def paramNames: Seq[Term.Name] =
      params.map(_.name.value).map(Term.Name(_))
  }

  final case class Trait(name: Type.Name, params: Seq[Type.Param], methods: Seq[Method]) {
    def unified: Type.Ref =
      if (params.isEmpty) {
        name
      } else {
        t"({type X[F[_]] = $name[..$paramTypes, F]})#X"
      }
    def paramTypes: Seq[Type] = params.map(_.name.value).map(Type.Name(_))
  }
  object Trait {
    def fromDefn(base: Defn.Trait): Trait = {
      val typeName = base.name
      val traitStats = base.templ.stats.get
      val (params, functor) = (base.tparams.dropRight(1), base.tparams.last.name)

      val methods = traitStats.map {
        case q"def $name[..$tps](..$params): ${someF: Type.Name }[$out]"
            if someF.value == functor.value =>
          Method(name, tps, params, out)
        case q"def $name: ${someF: Type.Name }[$out]" if someF.value == functor.value =>
          Method(name, Seq.empty, Seq.empty, out)
        case other =>
          abort(s"Illegal method [$other]")
      }
      Trait(typeName, params, methods)
    }
  }

  def parseTraitAndCompanion(defn: Any): Option[(Defn.Trait, Defn.Object)] =
    Some(defn).collect {
      case Term.Block(Seq(t: Defn.Trait, companion: Defn.Object)) =>
        (t, companion)
      case t: Defn.Trait =>
        (t, q"object ${Term.Name(t.name.value)}")
    }

}
