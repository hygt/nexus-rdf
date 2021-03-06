package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path.{segment, _}
import ch.epfl.bluebrain.nexus.rdf.Iri._
import org.scalatest.{EitherValues, Inspectors, Matchers, WordSpecLike}

class PathSpec extends WordSpecLike with Matchers with Inspectors with EitherValues {

  "A Path" should {
    val abcd = Path("/a/b//c/d")
    "be parsed in the correct ADT" in {
      // format: off
      val cases = List(
        ""                        -> Empty,
        "/"                       -> Slash(Empty),
        "///"                     -> Slash(Slash(Slash(Empty))),
        "/a/b//c/d"               -> Segment("d", Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b//c//"               -> Slash(Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b//:@//"              -> Slash(Slash(Segment(":@", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b//:://"              -> Slash(Slash(Segment("::", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b/%20/:://"           -> Slash(Slash(Segment("::", Slash(Segment(" ", Slash(Segment("b", Slash(Segment("a", Slash(Empty)))))))))),
        "/a£/bÆc//:://"           -> Slash(Slash(Segment("::", Slash(Slash(Segment("bÆc", Slash(Segment("a£", Slash(Empty))))))))),
        "/a%C2%A3/b%C3%86c//:://" -> Slash(Slash(Segment("::", Slash(Slash(Segment("bÆc", Slash(Segment("a£", Slash(Empty)))))))))
      )
      // format: on
      forAll(cases) {
        case (str, expected) =>
          Path(str).right.value shouldEqual expected
      }
    }
    "fail to construct for invalid chars" in {
      val cases = List("/a/b?", "abc", "/a#", ":asd", " ")
      forAll(cases) { c =>
        Path(c).left.value
      }
    }
    "normalize paths" in {
      val cases = List(
        ("/a/b/../c/", Slash(Segment("c", Slash(Segment("a", Slash(Empty))))), "/a/c/"),
        ("/../../../", Slash(Empty), "/"),
        ("/a/./b/./c/./", Slash(Segment("c", Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))), "/a/b/c/"),
        ("/a//../b/./c/./", Slash(Segment("c", Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))), "/a/b/c/"),
        ("/a/./b/../c/./", Slash(Segment("c", Slash(Segment("a", Slash(Empty))))), "/a/c/"),
        ("/a/c/../", Slash(Segment("a", Slash(Empty))), "/a/"),
        ("/a/c/./", Slash(Segment("c", Slash(Segment("a", Slash(Empty))))), "/a/c/")
      )
      forAll(cases) {
        case (str, expected, expectedStr) =>
          val value = Path(str).right.value
          value shouldEqual expected
          value.show shouldEqual expectedStr
      }
    }
    "return the correct information about the internal structure" in {
      val cases = List(
        ("", true, false, false, Some(Empty), None, None),
        ("/", false, true, false, None, Some(Slash(Empty)), None),
        ("/a/", false, true, false, None, Some(Slash(Segment("a", Slash(Empty)))), None),
        ("/a", false, false, true, None, None, Some(Segment("a", Slash(Empty))))
      )
      forAll(cases) {
        case (str, isEmpty, isSlash, isSegment, asEmpty, asSlash, asSegment) =>
          val p = Path(str).right.value
          p.isEmpty shouldEqual isEmpty
          p.isSlash shouldEqual isSlash
          p.isSegment shouldEqual isSegment
          p.asEmpty shouldEqual asEmpty
          p.asSlash shouldEqual asSlash
          p.asSegment shouldEqual asSegment
      }
    }
    "show" in {
      abcd.right.value.show shouldEqual "/a/b//c/d"
    }
    "be slash" in {
      Path("/").right.value.isSlash shouldEqual true
    }
    "not be slash" in {
      abcd.right.value.isSlash shouldEqual false
    }
    "end with slash" in {
      Path("/a/b//c/d/").right.value.endsWithSlash shouldEqual true
    }
    "not end with slash" in {
      abcd.right.value.endsWithSlash shouldEqual false
    }
    "show decoded" in {
      Path("/a%C2%A3/b%C3%86c//:://").right.value.show shouldEqual "/a£/bÆc//:://"
    }
    "eq" in {
      Eq.eqv(abcd.right.value, Segment("d", Path("/a/b//c/").right.value)) shouldEqual true
    }

    "start with slash" in {
      val cases = List("/", "///", "/a/b/c/d", "/a/b/c/d/")
      forAll(cases) {
        case (str) => Path(str).right.value.startWithSlash shouldEqual true
      }
    }

    "does not start with slash" in {
      val cases = List(Empty, Segment("d", Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Empty)))))))))
      forAll(cases) {
        case (p) => p.startWithSlash shouldEqual false
      }
    }

    "reverse" in {
      val cases = List(
        Path("/a/b").right.value    -> Slash(Segment("a", Slash(Segment("b", Empty)))),
        Empty                       -> Empty,
        Path("/a/b/c/").right.value -> Path("/c/b/a/").right.value,
        Path./                      -> Path./
      )
      forAll(cases) {
        case (path, reversed) =>
          path.reverse shouldEqual reversed
          path.reverse.reverse shouldEqual path
      }
    }

    "concatenate segments" in {
      segment("a").right.value / "b" / "c" shouldEqual Segment("c", Slash(Segment("b", Slash(Segment("a", Empty)))))
    }

    "join two paths" in {
      val cases = List(
        (Path("/e/f").right.value :: Path("/a/b/c/d").right.value)           -> Path("/a/b/c/d/e/f").right.value,
        (segment("ghi").right.value / "f" :: Path("/a/b/c/def").right.value) -> Path("/a/b/c/defghi/f").right.value,
        (Empty :: Path("/a/b").right.value)                                  -> Path("/a/b").right.value,
        (Empty :: Slash(Empty))                                              -> Slash(Empty),
        (Slash(Empty) :: Empty)                                              -> Slash(Empty),
        (Path("/e/f/").right.value :: Path("/a/b/c/d").right.value)          -> Path("/a/b/c/d/e/f/").right.value,
        (Path("/e/f/").right.value :: Path("/a/b/c/d/").right.value)         -> Path("/a/b/c/d//e/f/").right.value,
        (Empty :: Empty)                                                     -> Empty
      )
      forAll(cases) {
        case (result, expected) => result shouldEqual expected
      }
    }
  }
}
