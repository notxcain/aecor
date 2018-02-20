package aecor.tests

import catalysts.Platform
import cats.Eq
import cats.instances.AllInstances
import cats.syntax.{ AllSyntax, EqOps }
import org.scalactic.{ CanEqual, Equality, Equivalence, TripleEquals }
import org.scalactic.TripleEqualsSupport.{ AToBEquivalenceConstraint, BToAEquivalenceConstraint }
import org.scalactic.anyvals.{ PosInt, PosZDouble, PosZInt }
import org.scalatest.{ FunSuite, FunSuiteLike, Matchers }
import org.scalatest.prop.{ Configuration, GeneratorDrivenPropertyChecks }
import org.typelevel.discipline.scalatest.Discipline

trait TestSettings extends Configuration with Matchers {

  lazy val checkConfiguration: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = if (Platform.isJvm) PosInt(50) else PosInt(5),
      maxDiscardedFactor = if (Platform.isJvm) PosZDouble(5.0) else PosZDouble(50.0),
      minSize = PosZInt(0),
      sizeRange = if (Platform.isJvm) PosZInt(10) else PosZInt(5),
      workers = PosInt(1)
    )

  lazy val slowCheckConfiguration: PropertyCheckConfiguration =
    if (Platform.isJvm) checkConfiguration
    else PropertyCheckConfiguration(minSuccessful = 1, sizeRange = 1)
}

/**
  * An opinionated stack of traits to improve consistency and reduce
  * boilerplate. Copied from Cats library.
  */
trait LawSuite
    extends FunSuite
    with Matchers
    with GeneratorDrivenPropertyChecks
    with Discipline
    with TestSettings
    with AllInstances
    with AllSyntax
    with StrictCatsEquality { self: FunSuiteLike =>

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    checkConfiguration

  // disable Eq syntax (by making `catsSyntaxEq` not implicit), since it collides
  // with scalactic's equality
  override def catsSyntaxEq[A: Eq](a: A): EqOps[A] = new EqOps[A](a)

  def even(i: Int): Boolean = i % 2 == 0

  val evenPf: PartialFunction[Int, Int] = { case i if even(i) => i }
}

trait SlowLawSuite extends LawSuite {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    slowCheckConfiguration
}

final class CatsEquivalence[T](T: Eq[T]) extends Equivalence[T] {
  def areEquivalent(a: T, b: T): Boolean = T.eqv(a, b)
}

trait LowPriorityStrictCatsConstraints extends TripleEquals {
  implicit def lowPriorityCatsCanEqual[A, B](implicit B: Eq[B], ev: A <:< B): CanEqual[A, B] =
    new AToBEquivalenceConstraint[A, B](new CatsEquivalence(B), ev)
}

trait StrictCatsEquality extends LowPriorityStrictCatsConstraints {
  override def convertToEqualizer[T](left: T): Equalizer[T] = super.convertToEqualizer[T](left)
  implicit override def convertToCheckingEqualizer[T](left: T): CheckingEqualizer[T] =
    new CheckingEqualizer(left)
  override def unconstrainedEquality[A, B](implicit equalityOfA: Equality[A]): CanEqual[A, B] =
    super.unconstrainedEquality[A, B]
  implicit def catsCanEqual[A, B](implicit A: Eq[A], ev: B <:< A): CanEqual[A, B] =
    new BToAEquivalenceConstraint[A, B](new CatsEquivalence(A), ev)
}

object StrictCatsEquality extends StrictCatsEquality
