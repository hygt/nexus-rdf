package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Node.IriOrBNode
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.syntax.node._
import org.scalatest.{EitherValues, Inspectors, Matchers, WordSpecLike}
class NodeSpec extends WordSpecLike with Matchers with EitherValues with Inspectors {

  "A BNode" should {
    "be constructed correctly" in {
      val cases = List("a", "a-_", "a123")
      forAll(cases) { el =>
        Node.blank(el).right.value
      }
    }
    "fail to construct" in {
      val cases = List("", " ", "a#", "_", "-", "-a", "_a")
      forAll(cases) { el =>
        Node.blank(el).left.value
      }
    }
    "show" in {
      Node.blank.show
    }
    "eq" in {
      val lhs = Node.blank("a1").right.value
      val rhs = Node.blank("a1").right.value
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }

  "A LanguageTag" should {
    "be constructed correctly" in {
      val cases = List(
        "zh-guoyu",
        "sgn-BE-FR",
        "i-default",
        "en-GB-oed",
        "x-123asd-123asd78",
        "es-419",
        "en-US-x-twain",
        "de-Latn-DE-1996",
        "zh-Hans"
      )
      forAll(cases) { el =>
        LanguageTag(el).right.value
      }
    }
    "fail to construct" in {
      val cases = List(
        "",
        " ",
        "a",
        "213456475869707865433",
        "!",
        "aaaaaaaa4h5kj324h54"
      )
      forAll(cases) { el =>
        LanguageTag(el).left.value
      }
    }
  }

  "A Node" should {
    "expose the appropriate properties" in {
      val cases = List(
        (Node.blank, true, false, false),
        (Node.iri("https://a.b").right.value, false, true, false),
        (Node.literal(2), false, false, true)
      )
      forAll(cases) {
        case (node, isBlank, isIri, isLiteral) =>
          node.isBlank shouldEqual isBlank
          node.isIri shouldEqual isIri
          node.isLiteral shouldEqual isLiteral
          node.asBlank.isDefined shouldEqual isBlank
          node.asIri.isDefined shouldEqual isIri
          node.asLiteral.isDefined shouldEqual isLiteral
      }
    }
    "show" in {
      val cases = List[(Node, String)](
        (Node.blank("123").right.value, "_:123"),
        (Node.iri("https://a.b").right.value, "https://a.b"),
        (Node.iri("urn:ab:$").right.value, "urn:ab:$"),
        (Node.iri("urn:ab:£").right.value, "urn:ab:%C2%A3"),
        (Node.literal(2), """"2"^^<http://www.w3.org/2001/XMLSchema#integer>"""),
        (2, """"2"^^<http://www.w3.org/2001/XMLSchema#integer>"""),
        (2L, """"2"^^<http://www.w3.org/2001/XMLSchema#long>"""),
        (Node.literal(2.toLong), """"2"^^<http://www.w3.org/2001/XMLSchema#long>"""),
        (Node.literal(2.2), """"2.2"^^<http://www.w3.org/2001/XMLSchema#double>"""),
        (2.2, """"2.2"^^<http://www.w3.org/2001/XMLSchema#double>"""),
        (Node.literal(2.2f), """"2.2"^^<http://www.w3.org/2001/XMLSchema#float>"""),
        (2.2f, """"2.2"^^<http://www.w3.org/2001/XMLSchema#float>"""),
        (Node.literal(true), """"true"^^<http://www.w3.org/2001/XMLSchema#boolean>"""),
        (Node.literal(2.toShort), """"2"^^<http://www.w3.org/2001/XMLSchema#short>"""),
        (Node.literal(2.toByte), """"2"^^<http://www.w3.org/2001/XMLSchema#byte>"""),
        (Node.literal("a"), """"a""""),
        (Node.literal("a", LanguageTag("en").right.value), """"a"@en""")
      )
      forAll(cases) {
        case (node: Node, str) =>
          node.show shouldEqual str
      }
    }
    "eq" in {
      val cases = List[(Either[String, Node], Either[String, Node])](
        (Node.blank("123"), Node.blank("123")),
        (Node.iri("https://a.b"), Iri.absolute("https://a.b:443").map(Node.iri)),
        (Node.iri("urn:ab:$"), Node.iri("urn:ab:%24")),
        (Right(Node.literal(2)), Right(Node.literal("2", xsd.integer)))
      )
      forAll(cases) {
        case (lhs, rhs) =>
          Eq.eqv(lhs.right.value, rhs.right.value) shouldEqual true
      }
    }
  }

  "An IriOrBNode" should {
    "show" in {
      val cases = List[(IriOrBNode, String)](
        (Node.blank("123").right.value, "_:123"),
        (Node.iri("https://a.b").right.value, "https://a.b")
      )
      forAll(cases) {
        case (node: Node, str) =>
          node.show shouldEqual str
      }
    }
    "eq" in {
      val cases = List[(Either[String, IriOrBNode], Either[String, IriOrBNode])](
        (Node.blank("123"), Node.blank("123")),
        (Node.iri("https://a.b"), Iri.absolute("https://a.b:443").map(Node.iri)),
        (Node.iri("urn:ab:£"), Node.iri("urn:ab:%C2%A3"))
      )
      forAll(cases) {
        case (lhs, rhs) =>
          Eq.eqv(lhs.right.value, rhs.right.value) shouldEqual true
      }
    }
  }

  "A Literal" should {
    "be numeric" in {
      val cases = List(
        Node.literal(1),
        Node.literal(1.toByte),
        Node.literal(0.1.toFloat),
        Node.literal(0.1),
        Node.literal(1.toLong),
        Node.literal(1.toShort)
      )
      forAll(cases) { el =>
        el.isNumeric shouldEqual true
      }
    }
    "be string" in {
      Node.literal("a").isString shouldEqual true
      Node.literal("a", LanguageTag("en").right.value).isString shouldEqual true
    }
  }
}
