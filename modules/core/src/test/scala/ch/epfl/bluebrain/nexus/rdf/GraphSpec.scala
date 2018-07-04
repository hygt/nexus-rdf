package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Graph.{Triple, _}
import ch.epfl.bluebrain.nexus.rdf.Node.{IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.syntax.node._
import ch.epfl.bluebrain.nexus.rdf.syntax.node.unsafe._
import org.scalatest.{EitherValues, Matchers, WordSpecLike}

import scala.collection.immutable.HashSet

//noinspection TypeAnnotation
class GraphSpec extends WordSpecLike with Matchers with EitherValues {

  "An RDF Graph" should {
    val a      = url"http://a"
    val isa    = url"http://isa"
    val hasa   = url"http://hasa"
    val string = url"http://string"
    val bool   = url"http://bool"

    object p {
      val prefix = "http://prop"
      val string = url"$prefix/string"
      val int    = url"$prefix/int"
      val bool   = url"$prefix/bool"
      val other  = url"$prefix/other"
    }

    val triples = Set[(IriOrBNode, IriNode, Node)](
      (a, isa, string),
      (a, isa, bool),
      (a, hasa, b"1"),
      (b"1", p.string, "asd"),
      (b"1", p.int, 2),
      (b"1", p.bool, true)
    )

    val g = Graph(triples)

    "return the same collection of triples" in {
      g.triples shouldEqual triples
    }

    "add a new triple" in {
      val t: Triple = (b"1", p.int, 3)
      (g + t).triples shouldEqual (triples + t)
    }

    "subtract a triple" in {
      val t: Triple = (b"1", p.int, 2)
      (g - t).triples shouldEqual (triples - t)
    }

    "subtract a missing triple" in {
      val t: Triple = (b"1", p.int, 3)
      (g - t).triples shouldEqual triples
    }

    "union with another graph" in {
      val otherTriples = Set[Triple]((a, isa, string), (b"1", p.int, 4))
      val g2           = Graph(otherTriples)
      (g ++ g2).triples shouldEqual (triples + ((b"1", p.int, 4)))
    }

    "subtract a sub graph" in {
      ((g + ((b"1", p.other, true))) -- g).triples shouldEqual Set[Triple]((b"1", p.other, true))
    }

    "subtract a disjoint graph" in {
      val g2 = Graph((b"1", p.other, true): Triple)
      (g -- g2).triples shouldEqual g.triples
    }

    "ignore adding existing triples" in {
      (g + ((b"1", p.bool, true))).triples shouldEqual triples
    }

    "be cyclic" in {
      val g3 = Graph((a, hasa, b"1"), (b"1", isa, string), (b"1", hasa, a))
      g3.isCyclic shouldEqual true
      g3.isAcyclic shouldEqual false
    }

    "be acyclic" in {
      g.isCyclic shouldEqual false
      g.isAcyclic shouldEqual true
    }

    "be connected" in {
      g.isConnected shouldEqual true
    }

    "be unconnected" in {
      Graph(
        (a, isa, string),
        (b"1", isa, bool)
      ).isConnected shouldEqual false
    }

    "return the correct subjects" in {
      g.subjects() shouldEqual Set[IriOrBNode](a, b"1")
    }

    "return the correct subject" in {
      g.subjects(isa, string) shouldEqual Set(a)
    }

    "return the correct filtered subjects" in {
      g.subjects(p.string) shouldEqual Set[IriOrBNode](b"1")
      g.subjects(hasa) shouldEqual Set[IriOrBNode](a)
      g.subjects(n => n == p.string || n == hasa) shouldEqual Set[IriOrBNode](a, b"1")
    }

    "return no subject" in {
      g.subjects(a, b"10") shouldEqual Set.empty
    }

    "return the correct predicates" in {
      g.predicates() shouldEqual Set[IriNode](isa, hasa, p.string, p.int, p.bool)
    }

    "return the correct predicate" in {
      g.predicates(a, string) shouldEqual Set(isa)
    }

    "return the correct filtered predicates" in {
      g.predicates(a) shouldEqual Set(isa, hasa)
    }

    "return no predicate" in {
      g.predicates(a, b"10") shouldEqual Set.empty
    }

    "return the correct objects" in {
      g.objects() shouldEqual Set[Node](string, bool, b"1", "asd", 2, true)
    }

    "return the filtered objects" in {
      g.objects(a, isa) shouldEqual Set(string, bool)
      g.objects(b"1", p.string) shouldEqual Set[Node]("asd")
    }

    "return the filtered objects as String encoded" in {
      g.objects(b"1", p.string).map(_.as[String].right.value) shouldEqual Set("asd")
    }

    "return objects with predicate http://prop/string of http://prop/int" in {
      g.objects(a, isa) shouldEqual Set(string, bool)
      g.objects(p = pred => pred == p.string || pred == p.int) shouldEqual Set[Node]("asd", 2)
    }

    "return an empty list of filtered objects" in {
      g.objects(b"50", p.string) shouldEqual Set()
    }

    "show" in {
      val g = Graph((b"1", p.other, true): Triple)
      g.show shouldEqual s"(_:1 ${p.other.show} ${Literal(true).show})"
    }

    "eq" in {
      val fst = Graph(
        (b"1", p.other, true),
        (b"2", p.other, 2),
        (b"3", p.other, 3)
      )
      val snd = Graph(
        (b"1", p.other, true),
        (b"3", p.other, 3),
        (b"2", p.other, 2)
      )
      Eq.eqv(fst, snd) shouldEqual true
    }

    "not eq" in {
      val fst = Graph((b"1", p.other, true): Triple)
      val snd = Graph((b"1", p.other, false): Triple)
      Eq.eqv(fst, snd) shouldEqual false
    }

    "toString" in {
      val g = Graph((b"1", p.other, true): Triple)
      g.toString shouldEqual s"Graph(${b"1".toString}, ${Literal(true).toString}, ${b"1".toString}~>${Literal(true).toString} '${p.other.toString})"
    }

    "hash" in {
      val fst = Graph((b"1", p.other, true): Triple)
      val snd = Graph((b"1", p.other, true): Triple)
      HashSet(fst)(snd) shouldEqual true
    }
  }

}