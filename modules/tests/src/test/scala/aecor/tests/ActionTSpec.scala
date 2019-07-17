package aecor.tests

import aecor.data.Folded.Next
import aecor.data.{ ActionT, Fold, Folded }
import aecor.runtime.Eventsourced.Versioned
import cats.Id
import cats.data.{ Chain, NonEmptyChain }
import cats.instances.string._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.scalatest.funsuite.AnyFunSuite

class ActionTSpec extends AnyFunSuite {
  def append(s: String): ActionT[Id, String, String, Unit] = ActionT.append(NonEmptyChain.one(s))
  def read[S]: ActionT[Id, S, String, S] = ActionT.read
  def pure[A](a: A): ActionT[Id, String, String, A] = ActionT.pure(a)
  def fold(s: String) = Fold(s, (l: String, r: String) => Folded.next(l ++ r))
  def run[A](action: ActionT[Id, String, String, A]): Folded[(Chain[String], A)] =
    action.run(fold(""))

  test("ActionT.read associativity") {
    val n1 @ Next((es, out)) = run(append("a") >> (append("b") >> read))
    val n2 = run(append("a") >> append("b") >> read)
    assert(es === Chain("a", "b"))
    assert(out === "ab")
    assert(n1 === n2)
  }

  test("xmapState") {
    val Next((es, (out, versioned))) =
      (append("a") >> append("b") >> read)
        .expand[Versioned[String]]((s, v) => s.copy(value = v))(_.value)
        .zipWithRead
        .run(Versioned.fold(fold("")))
    println(versioned)
    assert(versioned.version == 2)
    assert(es === Chain("a", "b"))
    assert(out === "ab")
  }

  test("tailRecM") {
    val x: ActionT[Id, String, String, Unit] = 0.tailRecM { c =>
      if (c < 50000) {
        append(s"$c").as((c + 1).asLeft[Unit])
      } else {
        pure(().asRight)
      }
    }

    val Next((es, _)) = run(x)
    assert(es.size == 50000)
  }

}
