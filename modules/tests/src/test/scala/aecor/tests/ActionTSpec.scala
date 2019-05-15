package aecor.tests

import aecor.data.Folded.Next
import aecor.data.{ ActionT, Folded }
import cats.Id
import cats.data.{ Chain, NonEmptyChain }
import cats.instances.string._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import org.scalatest.FunSuite

class ActionTSpec extends FunSuite {
  def append(s: String): ActionT[Id, String, String, Unit] = ActionT.append(NonEmptyChain.one(s))
  def read: ActionT[Id, String, String, String] = ActionT.read

  def run[A](action: ActionT[Id, String, String, A]): Folded[(Chain[String], A)] =
    action.run("", (l, r) => (l ++ r).pure[Folded])

  test("ActionT.read associativity") {
    val n1 @ Next((es, out)) = run(append("a") >> (append("b") >> read))
    val n2 = run(append("a") >> append("b") >> read)
    assert(es === Chain("a", "b"))
    assert(out === "ab")
    assert(n1 === n2)
  }

}
