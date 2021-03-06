package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri._
import org.scalatest.{EitherValues, Inspectors, Matchers, WordSpecLike}

class IriSpec extends WordSpecLike with Matchers with Inspectors with EitherValues {
  "An Iri" should {
    val casesRelative = List(
      "//me:me@hOst:443/a/b?a&e=f&b=c#frag" -> "//me:me@host:443/a/b?a&b=c&e=f#frag",
      "//me:me@hOst#frag"                   -> "//me:me@host#frag",
      "/some/:/path"                        -> "/some/:/path"
    )
    val casesRelativeEncoded = List(
      "/some/!/path/€/other" -> "/some/!/path/%E2%82%AC/other"
    )
    val casesUrn = List(
      "urn:uUid:6e8bc430-9c3a-11d9-9669-0800200c9a66"      -> "urn:uuid:6e8bc430-9c3a-11d9-9669-0800200c9a66",
      "urn:example:a%C2%A3/b%C3%86c//:://?=a=b"            -> "urn:example:a£/bÆc//:://?=a=b",
      "urn:lex:eu:council:directive:2010-03-09;2010-19-UE" -> "urn:lex:eu:council:directive:2010-03-09;2010-19-UE"
    )
    val casesUrnEncoded = List(
      "urn:example:a%C2%A3/b%C3%86c//:://?=a=£"            -> "urn:example:a%C2%A3/b%C3%86c//:://?=a=%C2%A3",
      "urn:lex:eu:council:directive:2010-03-09;2010-19-UE" -> "urn:lex:eu:council:directive:2010-03-09;2010-19-UE"
    )
    val casesUrl = List(
      "hTtps://me:me@hOst:443/a/b?a&e=f&b=c#frag"   -> "https://me:me@host/a/b?a&b=c&e=f#frag",
      "hTtps://me:me@hOst#frag"                     -> "https://me:me@host#frag",
      "hTtp://hOst%C2%A3:80/a%C2%A3/b%C3%86c//:://" -> "http://host£/a£/bÆc//:://"
    )

    val casesUrlEncoded = List(
      "hTtp://hOst%C2%A3:80/a%C2%A3/b%C3%86c//%3A%3A//" -> "http://host%C2%A3/a%C2%A3/b%C3%86c//:://"
    )

    "be parsed correctly into a relative uri" in {
      forAll(casesRelative) {
        case (in, expected) =>
          val iri = Iri(in).right.value
          iri shouldEqual Iri.relative(in).right.value
          iri.asString shouldEqual expected
      }
    }

    "be parsed correctly into a urn" in {
      forAll(casesUrn) {
        case (in, expected) =>
          val iri = Iri(in).right.value
          iri shouldEqual Iri.urn(in).right.value
          iri.asString shouldEqual expected
      }
    }

    "be parsed correctly into a url" in {
      forAll(casesUrl) {
        case (in, expected) =>
          val iri = Iri(in).right.value
          iri shouldEqual Iri.url(in).right.value
          iri.asString shouldEqual expected
      }
    }

    "show url" in {
      forAll(casesUrlEncoded) {
        case (in, expected) =>
          Iri(in).right.value.show shouldEqual expected
      }
    }

    "show urn" in {
      forAll(casesUrnEncoded) {
        case (in, expected) =>
          Iri(in).right.value.show shouldEqual expected
      }
    }

    "show relative url" in {
      forAll(casesRelativeEncoded) {
        case (in, expected) =>
          Iri(in).right.value.show shouldEqual expected
      }
    }

    "eq relative" in {
      val lhs = Iri("/?q=asd#1").right.value
      val rhs = Iri("/?q=asd#1").right.value
      Eq.eqv(lhs, rhs) shouldEqual true
    }

    "eq urn" in {
      val lhs = Urn("urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").right.value
      val rhs = Urn("urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").right.value
      Eq.eqv(lhs, rhs) shouldEqual true
    }

    "eq url" in {
      val lhs = Iri("hTtp://gooGle.com/?q=asd#1").right.value
      val rhs = Iri("http://google.com/?q=asd#1").right.value
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }

}
