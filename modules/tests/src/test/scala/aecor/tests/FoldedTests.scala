package aecor.tests

import aecor.data.Folded
import cats.{ CoflatMap, Eval, Later, Monad, MonadError, Semigroupal }
import cats.laws.{ ApplicativeLaws, CoflatMapLaws, FlatMapLaws, MonadLaws }
import cats.laws.discipline._
import Folded.syntax._
import cats.tests.CatsSuite
import org.scalacheck.{ Arbitrary, Cogen }

class FoldedTests extends CatsSuite {

  implicit def arbitraryFolded[A](implicit A: Arbitrary[Option[A]]): Arbitrary[Folded[A]] =
    Arbitrary(A.arbitrary.map(_.map(_.next).getOrElse(impossible)))

  implicit def cogenFolded[A](implicit A: Cogen[Option[A]]): Cogen[Folded[A]] =
    A.contramap(_.toOption)

  checkAll("Folded[Int]", SemigroupalTests[Folded].semigroupal[Int, Int, Int])
  checkAll("Cartesian[Folded]", SerializableTests.serializable(Semigroupal[Folded]))

  checkAll("Folded[Int]", CoflatMapTests[Folded].coflatMap[Int, Int, Int])
  checkAll("CoflatMap[Folded]", SerializableTests.serializable(CoflatMap[Folded]))

  checkAll("Folded[Int]", MonadTests[Folded].monad[Int, Int, Int])
  checkAll("Monad[Folded]", SerializableTests.serializable(Monad[Folded]))

  checkAll("Folded with Unit", MonadErrorTests[Folded, Unit].monadError[Int, Int, Int])
  checkAll("MonadError[Folded, Unit]", SerializableTests.serializable(MonadError[Folded, Unit]))

  test("show") {
    impossible[Int].show should ===("Impossible")
    1.next.show should ===("Next(1)")

    forAll { fs: Folded[String] =>
      fs.show should ===(fs.toString)
    }
  }

  // The following tests check laws which are a different formulation of
  // laws that are checked. Since these laws are more or less duplicates of
  // existing laws, we don't check them for all types that have the relevant
  // instances.

  test("Kleisli associativity") {
    forAll { (l: Long, f: Long => Folded[Int], g: Int => Folded[Char], h: Char => Folded[String]) =>
      val isEq = FlatMapLaws[Folded].kleisliAssociativity(f, g, h, l)
      isEq.lhs should ===(isEq.rhs)
    }
  }

  test("Cokleisli associativity") {
    forAll { (l: Folded[Long], f: Folded[Long] => Int, g: Folded[Int] => Char, h: Folded[Char] => String) =>
      val isEq = CoflatMapLaws[Folded].cokleisliAssociativity(f, g, h, l)
      isEq.lhs should ===(isEq.rhs)
    }
  }

  test("applicative composition") {
    forAll { (fa: Folded[Int], fab: Folded[Int => Long], fbc: Folded[Long => Char]) =>
      val isEq = ApplicativeLaws[Folded].applicativeComposition(fa, fab, fbc)
      isEq.lhs should ===(isEq.rhs)
    }
  }

  val monadLaws = MonadLaws[Folded]

  test("Kleisli left identity") {
    forAll { (a: Int, f: Int => Folded[Long]) =>
      val isEq = monadLaws.kleisliLeftIdentity(a, f)
      isEq.lhs should ===(isEq.rhs)
    }
  }

  test("Kleisli right identity") {
    forAll { (a: Int, f: Int => Folded[Long]) =>
      val isEq = monadLaws.kleisliRightIdentity(a, f)
      isEq.lhs should ===(isEq.rhs)
    }
  }

  test("map2Eval is lazy") {
    val bomb: Eval[Folded[Int]] = Later(sys.error("boom"))
    impossible[Int].map2Eval(bomb)(_ + _).value should ===(impossible[Int])
  }
}
