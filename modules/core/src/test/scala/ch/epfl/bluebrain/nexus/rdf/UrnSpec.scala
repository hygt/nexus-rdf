package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri._
import org.scalatest.{EitherValues, Inspectors, Matchers, WordSpecLike}

class UrnSpec extends WordSpecLike with Matchers with Inspectors with EitherValues {

  "An Urn" should {
    "be parsed correctly" in {
      // format: off
      val cases = List(
        "urn:uUid:6e8bc430-9c3a-11d9-9669-0800200c9a66"           -> "urn:uuid:6e8bc430-9c3a-11d9-9669-0800200c9a66",
        "urn:example:a%C2%A3/b%C3%86c//:://?=a=b#"                -> "urn:example:a£/bÆc//:://?=a=b#",
        "urn:lex:eu:council:directive:2010-03-09;2010-19-UE"      -> "urn:lex:eu:council:directive:2010-03-09;2010-19-UE",
        "urn:Example:weather?=op=map&lat=39.56&lon=-104.85#test"  -> "urn:example:weather?=lat=39.56&lon=-104.85&op=map#test",
        "urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk"           -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk",
        "urn:examp-lE:foo-bar-baz-qux?=a=b?+CCResolve:cc=uk"      -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b",
        "urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b"      -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b",
        "urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash" -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash"
      )
      // format: on
      forAll(cases) {
        case (in, expected) =>
          Urn(in).right.value.asString shouldEqual expected
      }
    }

    "fail to parse" in {
      val fail = List(
        "urn:example:some/path/?+",
        "urn:example:some/path/?=",
      )
      forAll(fail) { str =>
        Urn(str).left.value
      }
    }

    val withHash = Iri.absolute("urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").right

    "be absolute" in {
      withHash.value.isAbsolute shouldEqual true
    }

    "be a Urn" in {
      withHash.value.isUrn shouldEqual true
    }

    "show" in {
      Iri
        .absolute("urn:example:a£/bÆc//:://?=a=b#")
        .right
        .value
        .show shouldEqual "urn:example:a%C2%A3/b%C3%86c//:://?=a=b#"
    }

    "return an optional self" in {
      withHash.value.asUrn shouldEqual Some(withHash.value)
    }

    "return an optional self from asAbsolute" in {
      withHash.value.asAbsolute shouldEqual Some(withHash.value)
    }

    "not be an Url" in {
      withHash.value.isUrl shouldEqual false
    }

    "not return a url" in {
      withHash.value.asUrl shouldEqual None
    }

    "not be a RelativeIri" in {
      withHash.value.isRelative shouldEqual false
    }

    "not return a RelativeIri" in {
      withHash.value.asRelative shouldEqual None
    }

    "eq" in {
      val lhs = Urn("urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").right.value
      val rhs = Urn("urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").right.value
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }
}
