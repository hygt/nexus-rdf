package ch.epfl.bluebrain.nexus.rdf

import cats.syntax.either._
import cats.syntax.show._
import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.Iri.Host.{IPv4Host, IPv6Host, NamedHost}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri._
import ch.epfl.bluebrain.nexus.rdf.PctString._

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.collection.{immutable, SeqView, SortedMap}

/**
  * An Iri as defined by RFC 3987.
  */
sealed abstract class Iri extends Product with Serializable {

  /**
    * @return true if this Iri is absolute, false otherwise
    */
  def isAbsolute: Boolean

  /**
    * @return Some(this) if this Iri is absolute, None otherwise
    */
  def asAbsolute: Option[AbsoluteIri]

  /**
    * @return true if this Iri is relative, false otherwise
    */
  def isRelative: Boolean = !isAbsolute

  /**
    * @return Some(this) if this Iri is relative, None otherwise
    */
  def asRelative: Option[RelativeIri]

  /**
    * @return true if this Iri is an Url, false otherwise
    */
  def isUrl: Boolean

  /**
    * @return Some(this) if this Iri is an Url, None otherwise
    */
  def asUrl: Option[Url]

  /**
    * @return true if this Iri is an Urn, false otherwise
    */
  def isUrn: Boolean

  /**
    * @return Some(this) if this Iri is an Urn, None otherwise
    */
  def asUrn: Option[Urn]

  /**
    * @return the UTF-8 string representation of this Iri
    */
  def asString: String

  /**
    * @return the string representation of this Iri as a valid Uri
    *         (using percent-encoding when required acording to rfc3986)
    */
  def asUri: String

  override def toString: String = asString
}

object Iri {

  /**
    * Attempt to construct a new Url from the argument validating the structure and the character encodings as per
    * RFC 3987.
    *
    * @param string the string to parse as an absolute url.
    * @return Right(url) if the string conforms to specification, Left(error) otherwise
    */
  final def url(string: String): Either[String, Url] =
    Url(string)

  /**
    * Attempt to construct a new Urn from the argument validating the structure and the character encodings as per
    * RFC 3987 and 8141.
    *
    * @param string the string to parse as an Urn.
    * @return Right(urn) if the string conforms to specification, Left(error) otherwise
    */
  final def urn(string: String): Either[String, Urn] =
    Urn(string)

  /**
    * Attempt to construct a new AbsoluteIri (Url or Urn) from the argument as per the RFC 3987 and 8141.
    *
    * @param string the string to parse as an absolute iri.
    * @return Right(AbsoluteIri) if the string conforms to specification, Left(error) otherwise
    */
  final def absolute(string: String): Either[String, AbsoluteIri] =
    new IriParser(string).parseAbsolute

  /**
    * Attempt to construct a new RelativeIri from the argument as per the RFC 3987.
    *
    * @param string the string to parse as a relative iri
    * @return Right(RelativeIri) if the string conforms to specification, Left(error) otherwise
    */
  final def relative(string: String): Either[String, RelativeIri] =
    RelativeIri(string)

  /**
    * Attempt to construct a new Iri (Url, Urn or RelativeIri) from the argument as per the RFC 3987 and 8141.
    *
    * @param string the string to parse as an iri.
    * @return Right(Iri) if the string conforms to specification, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Iri] =
    absolute(string) orElse relative(string)

  @SuppressWarnings(Array("EitherGet"))
  private[rdf] final def unsafe(string: String): Iri =
    apply(string).right.get

  /**
    * A relative IRI.
    *
    * @param authority the optional authority part
    * @param path      the path part
    * @param query     an optional query part
    * @param fragment  an optional fragment part
    */
  final case class RelativeIri(authority: Option[Authority],
                               path: Path,
                               query: Option[Query],
                               fragment: Option[Fragment])
      extends Iri {
    override def isAbsolute: Boolean             = false
    override def asAbsolute: Option[AbsoluteIri] = None
    override def isUrl: Boolean                  = false
    override def isUrn: Boolean                  = false
    override def asUrn: Option[Urn]              = None
    override def asUrl: Option[Url]              = None
    override def asRelative: Option[RelativeIri] = Some(this)

    override lazy val asString: String = {
      val a = authority.map("//" + _.asString).getOrElse("")
      val q = query.map("?" + _.asString).getOrElse("")
      val f = fragment.map("#" + _.asString).getOrElse("")

      s"$a${path.asString}$q$f"
    }

    override lazy val asUri: String = {
      val a = authority.map("//" + _.pctEncoded).getOrElse("")
      val q = query.map("?" + _.pctEncoded).getOrElse("")
      val f = fragment.map("#" + _.pctEncoded).getOrElse("")

      s"$a${path.pctEncoded}$q$f"
    }

    /**
      * Resolves a [[RelativeIri]] into an [[AbsoluteIri]] with the provided ''base''.
      * The resolution algorithm is taken from the rfc3986 and
      * can be found in https://tools.ietf.org/html/rfc3986#section-5.2.
      *
      * Ex: given a base = "http://a/b/c/d;p?q" and a relative iri = "./g" the output will be
      * "http://a/b/c/g"
      *
      * @param base the base [[AbsoluteIri]] from where to resolve the [[RelativeIri]]
      */
    def resolve(base: AbsoluteIri): AbsoluteIri =
      base match {
        case url: Url => resolveUrl(url)
        case urn: Urn => resolveUrn(urn)
      }

    private def resolveUrl(base: Url): AbsoluteIri =
      authority match {
        case Some(_) =>
          Url(base.scheme, authority, path, query, fragment)
        case None =>
          val (p, q) = path match {
            case Empty =>
              base.path -> (if (query.isDefined) query else base.query)
            case _ if path.startWithSlash =>
              path -> query
            case _ =>
              merge(base) -> query
          }
          base.copy(query = q, path = p, fragment = fragment)
      }

    private def resolveUrn(base: Urn): AbsoluteIri = {
      val (p, q) = path match {
        case Empty =>
          base.nss -> (if (query.isDefined) query else base.q)
        case _ if path.startWithSlash =>
          path -> query
        case _ =>
          merge(base) -> query
      }
      base.copy(q = q, nss = p, fragment = fragment)
    }

    private def merge(base: Url): Path =
      if (base.authority.isDefined && base.path.isEmpty) Slash(path)
      else removeDotSegments(path :: deleteLast(base.path))

    private def merge(base: Urn): Path =
      removeDotSegments(path :: deleteLast(base.nss))

    private def deleteLast(path: Path, withSlash: Boolean = false): Path =
      path match {
        case Segment(_, Slash(rest)) if withSlash => rest
        case Segment(_, rest)                     => rest
        case _                                    => path
      }

    private def removeDotSegments(path: Path): Path = {

      @tailrec
      def inner(input: Path, output: Path): Path =
        input match {
          // -> "../" or "./"
          case Segment("..", Slash(rest)) => inner(rest, output)
          case Segment(".", Slash(rest))  => inner(rest, output)
          // -> "/./" or "/.",
          case Slash(Segment(".", Slash(rest))) => inner(Slash(rest), output)
          case Slash(Segment(".", rest))        => inner(Slash(rest), output)
          // -> "/../" or "/.."
          case Slash(Segment("..", Slash(rest))) => inner(Slash(rest), deleteLast(output, withSlash = true))
          case Slash(Segment("..", rest))        => inner(Slash(rest), deleteLast(output, withSlash = true))
          // only "." or ".."
          case Segment(".", Empty) | Segment("..", Empty) => inner(Path.Empty, output)
          // move
          case Slash(rest)      => inner(rest, Slash(output))
          case Segment(s, rest) => inner(rest, output + s)
          case Empty            => output
        }

      inner(path.reverse, Path.Empty)
    }

  }

  object RelativeIri {

    /**
      * Attempt to construct a new RelativeIri from the argument validating the structure and the character encodings as per
      * RFC 3987.
      *
      * @param string the string to parse as a relative IRI.
      * @return Right(url) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, RelativeIri] =
      new IriParser(string).parseRelative

    final implicit val relativeIriShow: Show[RelativeIri] = Show.show(_.asUri)

    final implicit val relativeIriEq: Eq[RelativeIri] = Eq.fromUniversalEquals
  }

  /**
    * An absolute Iri as defined by RFC 3987.
    */
  sealed abstract class AbsoluteIri extends Iri {
    override def isAbsolute: Boolean             = true
    override def asAbsolute: Option[AbsoluteIri] = Some(this)
    override def asRelative: Option[RelativeIri] = None

    /**
      * Appends the segment to the end of the ''path'' section of the current ''AbsoluteIri''. If the path section ends with slash it
      * directly appends, otherwise it adds a slash before appending
      *
      * @param segment the segment to be appended to the end of the path section
      */
    def +(segment: String): AbsoluteIri
  }

  object AbsoluteIri {
    final implicit val absoluteIriShow: Show[AbsoluteIri] = Show.show(_.asUri)
    final implicit val absoluteIriEq: Eq[AbsoluteIri]     = Eq.fromUniversalEquals
  }

  /**
    * An absolute Url.
    *
    * @param scheme    the scheme part
    * @param authority an optional authority part
    * @param path      the path part
    * @param query     an optional query part
    * @param fragment  an optional fragment part
    */
  final case class Url(
      scheme: Scheme,
      authority: Option[Authority],
      path: Path,
      query: Option[Query],
      fragment: Option[Fragment]
  ) extends AbsoluteIri {
    override def isUrl: Boolean     = true
    override def asUrl: Option[Url] = Some(this)
    override def isUrn: Boolean     = false
    override def asUrn: Option[Urn] = None
    override def +(segment: String): AbsoluteIri =
      if (path.endsWithSlash) copy(path = path + segment) else copy(path = path / segment)

    /**
      * Returns a copy of this Url with the fragment value set to the argument fragment.
      *
      * @param fragment the fragment to replace
      * @return a copy of this Url with the fragment value set to the argument fragment
      */
    def withFragment(fragment: Fragment): Url =
      copy(fragment = Some(fragment))

    override lazy val asString: String = {
      val a = authority.map("//" + _.asString).getOrElse("")
      val q = query.map("?" + _.asString).getOrElse("")
      val f = fragment.map("#" + _.asString).getOrElse("")

      s"${scheme.value}:$a${path.asString}$q$f"
    }

    override lazy val asUri: String = {
      val a = authority.map("//" + _.pctEncoded).getOrElse("")
      val q = query.map("?" + _.pctEncoded).getOrElse("")
      val f = fragment.map("#" + _.pctEncoded).getOrElse("")

      s"${scheme.value}:$a${path.pctEncoded}$q$f"
    }
  }

  object Url {

    private val defaultSchemePortMapping: Map[Scheme, Port] = Map(
      "ftp"    -> 21,
      "ssh"    -> 22,
      "telnet" -> 23,
      "smtp"   -> 25,
      "domain" -> 53,
      "tftp"   -> 69,
      "http"   -> 80,
      "ws"     -> 80,
      "pop3"   -> 110,
      "nntp"   -> 119,
      "imap"   -> 143,
      "snmp"   -> 161,
      "ldap"   -> 389,
      "https"  -> 443,
      "wss"    -> 443,
      "imaps"  -> 993,
      "nfs"    -> 2049
    ).map { case (s, p) => (new Scheme(s), new Port(p)) }

    private def normalize(scheme: Scheme, authority: Authority): Authority =
      if (authority.port == defaultSchemePortMapping.get(scheme)) authority.copy(port = None) else authority

    /**
      * Constructs an Url from its constituents.
      *
      * @param scheme    the scheme part
      * @param authority an optional authority part
      * @param path      the path part
      * @param query     an optional query part
      * @param fragment  an optional fragment part
      */
    final def apply(
        scheme: Scheme,
        authority: Option[Authority],
        path: Path,
        query: Option[Query],
        fragment: Option[Fragment]
    ): Url = new Url(scheme, authority.map(normalize(scheme, _)), path, query, fragment)

    /**
      * Attempt to construct a new Url from the argument validating the structure and the character encodings as per
      * RFC 3987.
      *
      * @param string the string to parse as an absolute url.
      * @return Right(url) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Url] =
      new IriParser(string).parseUrl

    @SuppressWarnings(Array("EitherGet"))
    private[rdf] def unsafe(string: String): Url =
      apply(string).right.get

    final implicit def urlShow: Show[Url] = Show.show(_.asUri)

    final implicit val urlEq: Eq[Url] = Eq.fromUniversalEquals
  }

  /**
    * Scheme part of an Iri as defined by RFC 3987.
    *
    * @param value the string value of the scheme
    */
  final case class Scheme private[rdf] (value: String) {

    /**
      * Whether this scheme identifies an URN.
      */
    def isUrn: Boolean = value equalsIgnoreCase "urn"

    /**
      * Whether this scheme identifies an HTTPS Iri.
      */
    def isHttps: Boolean = value equalsIgnoreCase "https"

    /**
      * Whether this scheme identifies an HTTP Iri.
      */
    def isHttp: Boolean = value equalsIgnoreCase "http"
  }

  object Scheme {

    /**
      * Attempts to construct a scheme from the argument string value.  The provided value, if correct, will be normalized
      * such that:
      * {{{
      *   Scheme("HTTPS").right.get.value == "https"
      * }}}
      *
      * @param value the string representation of the scheme
      * @return Right(value) if successful or Left(error) if the string does not conform to the RFC 3987 format
      */
    final def apply(value: String): Either[String, Scheme] =
      new IriParser(value).parseScheme

    final implicit val schemeShow: Show[Scheme] = Show.show(_.value)
    final implicit val schemeEq: Eq[Scheme]     = Eq.fromUniversalEquals
  }

  /**
    * The Authority part of an IRI as defined by RFC 3987.
    *
    * @param userInfo the optional user info part
    * @param host     the host part
    * @param port     the optional port part
    */
  final case class Authority(userInfo: Option[UserInfo], host: Host, port: Option[Port]) {

    /**
      * @return the UTF-8 representation
      */
    lazy val asString: String = {
      val ui = userInfo.map(_.asString + "@").getOrElse("")
      val p  = port.map(":" + _.value.toString).getOrElse("")
      s"$ui${host.asString}$p"
    }

    /**
      * @return the string representation using percent-encoding for any character that
      *         is not contained in the Set ''pchar''
      */
    lazy val pctEncoded: String = {
      {
        val ui = userInfo.map(_.pctEncoded + "@").getOrElse("")
        val p  = port.map(":" + _.value.toString).getOrElse("")
        s"$ui${host.pctEncoded}$p"
      }
    }
  }

  object Authority {

    final implicit val authorityShow: Show[Authority] = Show.show(_.asString)

    final implicit val authorityEq: Eq[Authority] = Eq.fromUniversalEquals
  }

  /**
    * A user info representation as specified by RFC 3987.
    *
    * @param value the underlying string representation
    */
  final case class UserInfo private[rdf] (value: String) {

    /**
      * As per the specification the user info is case sensitive.  This method allows comparing two user info values
      * disregarding the character casing.
      *
      * @param that the user info to compare to
      * @return true if the underlying values are equal (diregarding their case), false otherwise
      */
    def equalsIgnoreCase(that: UserInfo): Boolean =
      this.value equalsIgnoreCase that.value

    /**
      * @return the UTF-8 representation
      */
    lazy val asString: String =
      value

    /**
      * @return the string representation using percent-encoding for any character that
      *         is not contained in the Set ''pchar''
      */
    lazy val pctEncoded: String =
      pctEncode(value)
  }

  object UserInfo {

    /**
      * Attempt to construct a new UserInfo from the argument validating the character encodings as per RFC 3987.
      *
      * @param string the string to parse as a user info.
      * @return Right(UserInfo(value)) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, UserInfo] =
      new IriParser(string).parseUserInfo

    final implicit val userInfoShow: Show[UserInfo] = Show.show(_.asString)
    final implicit val userInfoEq: Eq[UserInfo]     = Eq.fromUniversalEquals
  }

  /**
    * Host part of an Iri as defined in RFC 3987.
    */
  sealed abstract class Host extends Product with Serializable with PctString {

    /**
      * @return true if the host is an IPv4 address, false otherwise
      */
    def isIPv4: Boolean = false

    /**
      * @return true if the host is an IPv6 address, false otherwise
      */
    def isIPv6: Boolean = false

    /**
      * @return true if the host is a named host, false otherwise
      */
    def isNamed: Boolean = false

    /**
      * @return Some(this) if this is an IPv4Host, None otherwise
      */
    def asIPv4: Option[IPv4Host] = None

    /**
      * @return Some(this) if this is an IPv6Host, None otherwise
      */
    def asIPv6: Option[IPv6Host] = None

    /**
      * @return Some(this) if this is a NamedHost, None otherwise
      */
    def asNamed: Option[NamedHost] = None
  }

  object Host {

    /**
      * Constructs a new IPv4Host from the argument bytes.
      *
      * @param byte1 the first byte of the address
      * @param byte2 the second byte of the address
      * @param byte3 the third byte of the address
      * @param byte4 the fourth byte of the address
      * @return the IPv4Host represented by these bytes
      */
    final def ipv4(byte1: Byte, byte2: Byte, byte3: Byte, byte4: Byte): IPv4Host =
      IPv4Host(byte1, byte2, byte3, byte4)

    /**
      * Attempt to construct a new IPv4Host from its 32bit representation.
      *
      * @param bytes a 32bit IPv4 address
      * @return Right(IPv4Host(bytes)) if the bytes is a 4byte array, Left(error) otherwise
      */
    final def ipv4(bytes: Array[Byte]): Either[String, IPv4Host] =
      IPv4Host(bytes)

    /**
      * Attempt to construct a new IPv4Host from its string representation as specified by RFC 3987.
      *
      * @param string the string to parse as an IPv4 address.
      * @return Right(IPv4Host(bytes)) if the string conforms to specification, Left(error) otherwise
      */
    final def ipv4(string: String): Either[String, IPv4Host] =
      IPv4Host(string)

    /**
      * Attempt to construct a new IPv6Host from its 128bit representation.
      *
      * @param bytes a 128bit IPv6 address
      * @return Right(IPv6Host(bytes)) if the bytes is a 16byte array, Left(error) otherwise
      */
    def ipv6(bytes: Array[Byte]): Either[String, IPv6Host] =
      IPv6Host(bytes)

    /**
      * Attempt to construct a new NamedHost from the argument validating the character encodings as per RFC 3987.
      *
      * @param string the string to parse as a named host.
      * @return Right(NamedHost(value)) if the string conforms to specification, Left(error) otherwise
      */
    def named(string: String): Either[String, NamedHost] =
      NamedHost(string)

    /**
      * An IPv4 host representation as specified by RFC 3987.
      *
      * @param bytes the underlying bytes
      */
    final case class IPv4Host private[rdf] (bytes: immutable.Seq[Byte]) extends Host {
      override def isIPv4: Boolean          = true
      override def asIPv4: Option[IPv4Host] = Some(this)
      override lazy val value: String       = bytes.map(_ & 0xFF).mkString(".")
    }

    object IPv4Host {

      /**
        * Attempt to construct a new IPv4Host from its 32bit representation.
        *
        * @param bytes a 32bit IPv4 address
        * @return Right(IPv4Host(bytes)) if the bytes is a 4byte array, Left(error) otherwise
        */
      final def apply(bytes: Array[Byte]): Either[String, IPv4Host] =
        Either
          .catchNonFatal(fromBytes(bytes))
          .leftMap(_ => "Illegal IPv4Host byte representation")

      /**
        * Attempt to construct a new IPv4Host from its string representation as specified by RFC 3987.
        *
        * @param string the string to parse as an IPv4 address.
        * @return Right(IPv4Host(bytes)) if the string conforms to specification, Left(error) otherwise
        */
      final def apply(string: String): Either[String, IPv4Host] =
        new IriParser(string).parseIPv4

      /**
        * Constructs a new IPv4Host from the argument bytes.
        *
        * @param byte1 the first byte of the address
        * @param byte2 the second byte of the address
        * @param byte3 the third byte of the address
        * @param byte4 the fourth byte of the address
        * @return the IPv4Host represented by these bytes
        */
      final def apply(byte1: Byte, byte2: Byte, byte3: Byte, byte4: Byte): IPv4Host =
        fromBytes(Array(byte1, byte2, byte3, byte4))

      private def fromBytes(bytes: Array[Byte]): IPv4Host = {
        require(bytes.length == 4)
        new IPv4Host(immutable.Seq(bytes: _*))
      }

      final implicit val ipv4HostShow: Show[IPv4Host] =
        Show.show(_.asString)

      final implicit val ipv4HostEq: Eq[IPv4Host] =
        Eq.fromUniversalEquals
    }

    /**
      * An IPv6 host representation as specified by RFC 3987.
      *
      * @param bytes the underlying bytes
      */
    final case class IPv6Host private[rdf] (bytes: immutable.Seq[Byte]) extends Host {
      override def isIPv6: Boolean          = true
      override def asIPv6: Option[IPv6Host] = Some(this)

      override lazy val value: String =
        asString(bytes.view)

      lazy val asMixedString: String =
        asString(bytes.view(0, 12)) + ":" + bytes.view(12, 16).map(_ & 0xFF).mkString(".")

      private def asString(bytes: SeqView[Byte, immutable.Seq[Byte]]): String =
        bytes.grouped(2).map(two => Integer.toHexString(BigInt(two.toArray).intValue())).mkString(":")
    }

    object IPv6Host {

      /**
        * Attempt to construct a new IPv6Host from its 128bit representation.
        *
        * @param bytes a 128bit IPv6 address
        * @return Right(IPv6Host(bytes)) if the bytes is a 16byte array, Left(error) otherwise
        */
      final def apply(bytes: Array[Byte]): Either[String, IPv6Host] =
        Either
          .catchNonFatal(fromBytes(bytes))
          .leftMap(_ => "Illegal IPv6Host byte representation")

      private def fromBytes(bytes: Array[Byte]): IPv6Host = {
        require(bytes.length == 16)
        new IPv6Host(immutable.Seq(bytes: _*))
      }

      final implicit val ipv6HostShow: Show[IPv6Host] =
        Show.show(_.asString)

      final implicit val ipv6HostEq: Eq[IPv6Host] =
        Eq.fromUniversalEquals
    }

    /**
      * A named host representation as specified by RFC 3987.
      *
      * @param value the underlying string representation
      */
    final case class NamedHost private[rdf] (value: String) extends Host {
      override def isNamed: Boolean           = true
      override def asNamed: Option[NamedHost] = Some(this)
    }

    object NamedHost {

      /**
        * Attempt to construct a new NamedHost from the argument validating the character encodings as per RFC 3987.
        *
        * @param string the string to parse as a named host.
        * @return Right(NamedHost(value)) if the string conforms to specification, Left(error) otherwise
        */
      final def apply(string: String): Either[String, NamedHost] =
        new IriParser(string).parseNamed

      final implicit val namedHostShow: Show[NamedHost] =
        Show.show(_.asString)

      final implicit val namedHostEq: Eq[NamedHost] =
        Eq.fromUniversalEquals
    }

    final implicit val hostShow: Show[Host] = Show.show(_.asString)
  }

  /**
    * Port part of an Iri as defined by RFC 3987.
    *
    * @param value the underlying int value
    */
  final case class Port private[rdf] (value: Int)

  object Port {

    /**
      * Attempts to construct a port from the argument int value.  Valid values are [0, 65535].
      *
      * @param value the underlying port value
      * @return Right(port) if successful, Left(error) otherwise
      */
    final def apply(value: Int): Either[String, Port] =
      if (value >= 0 && value < 65536) Right(new Port(value))
      else Left("Port value must in range [0, 65535]")

    /**
      * Attempts to construct a port from the argument string value.  Valid values are [0, 65535] without leading zeroes.
      *
      * @param string the string representation of the port
      * @return Right(port) if successful, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Port] =
      new IriParser(string).parsePort

    final implicit val portShow: Show[Port] = Show.show(_.value.toString)
    final implicit val portEq: Eq[Port]     = Eq.fromUniversalEquals
  }

  /**
    * Path part of an Iri as defined in RFC 3987.
    */
  sealed abstract class Path extends Product with Serializable {

    /**
      * @return true if the path contains no characters, false otherwise
      */
    def isEmpty: Boolean

    /**
      * @return false if the path contains no characters, true otherwise
      */
    def nonEmpty: Boolean = !isEmpty

    /**
      * @return true if this path is a [[ch.epfl.bluebrain.nexus.rdf.Iri.Path.Slash]] (ends with a slash '/'), false otherwise
      */
    def isSlash: Boolean

    /**
      * @return true if this path is a [[ch.epfl.bluebrain.nexus.rdf.Iri.Path.Segment]] (ends with a segment), false otherwise
      */
    def isSegment: Boolean

    /**
      * @return Some(this) if this path is an Empty path, None otherwise
      */
    def asEmpty: Option[Empty]

    /**
      * @return Some(this) if this path is a Slash (ends with a slash '/') path, None otherwise
      */
    def asSlash: Option[Slash]

    /**
      * @return Some(this) if this path is a Segment (ends with a segment) path, None otherwise
      */
    def asSegment: Option[Segment]

    /**
      * @return true if this path ends with a slash ('/'), false otherwise
      */
    def endsWithSlash: Boolean = isSlash

    /**
      * @return true if this path starts with a slash ('/'), false otherwise
      */
    def startWithSlash: Boolean

    /**
      * @return the UTF-8 representation of this Path
      */
    def asString: String

    /**
      * @return the string representation using percent-encoding for any character that
      *         is not contained in the Set ''pchar''
      */
    def pctEncoded: String

    /**
      * @return the reversed path
      */
    def reverse: Path = {
      @tailrec
      def inner(acc: Path, remaining: Path): Path = remaining match {
        case Empty         => acc
        case Segment(h, t) => inner(Segment(h, acc), t)
        case Slash(t)      => inner(Slash(acc), t)
      }
      inner(Empty, this)
    }

    /**
      * @param other the path to be suffixed to the current path
      * @return the current path plus the provided path
      *         Ex: current = "/a/b/c/d", other = "/e/f" will output "/a/b/c/d/e/f"
      *         current = "/a/b/c/def", other = "ghi/f" will output "/a/b/c/def/ghi/f"
      */
    def ::(other: Path): Path = other prepend this

    /**
      * @param other the path to be prepended to the current path
      * @return the current path plus the provided path
      *         Ex: current = "/e/f", other = "/a/b/c/d" will output "/a/b/c/d/e/f"
      *         current = "ghi/f", other = "/a/b/c/def" will output "/a/b/c/def/ghi/f"
      **/
    def prepend(other: Path): Path

    /**
      * @param segment the segment to be appended to a path
      * @return current / segment. If the current path is a [[Segment]], a slash will be added
      */
    def /(segment: String): Path =
      if (segment.isEmpty) this else Segment(segment, Slash(this))

    /**
      * @param string the string to be appended to this path
      * @return Segment(string, Empty) if this is empty, current / string if current ends with a slash
      *         or Segment(segment + string, rest) if current is a Segment
      */
    def +(string: String): Path
  }

  object Path {

    /**
      * Constant value for a single slash.
      */
    final val / = Slash(Empty)

    /**
      * Attempts to parse the argument string as an `ipath-abempty` Path as defined by RFC 3987.
      *
      * @param string the string to parse as a Path
      * @return Right(Path) if the parsing succeeds, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Path] =
      abempty(string)

    /**
      * Attempts to parse the argument string as an `ipath-abempty` Path as defined by RFC 3987.
      *
      * @param string the string to parse as a Path
      * @return Right(Path) if the parsing succeeds, Left(error) otherwise
      */
    final def abempty(string: String): Either[String, Path] =
      new IriParser(string).parsePathAbempty

    /**
      * Attempts to parse the argument string as an `isegment-nz` Segment as defined by RFC 3987.
      *
      * @param string the string to parse as a Path
      * @return Right(Path) if the parsing succeeds, Left(error) otherwise
      */
    final def segment(string: String): Either[String, Path] =
      new IriParser(string).parsePathSegment

    /**
      * An empty path.
      */
    sealed trait Empty extends Path

    /**
      * An empty path.
      */
    final case object Empty extends Empty {
      def isEmpty: Boolean           = true
      def isSlash: Boolean           = false
      def isSegment: Boolean         = false
      def asEmpty: Option[Empty]     = Some(this)
      def asSlash: Option[Slash]     = None
      def asSegment: Option[Segment] = None
      def asString: String           = ""
      def pctEncoded: String         = ""
      def startWithSlash: Boolean    = false
      def prepend(other: Path): Path = other
      def +(segment: String): Path   = if (segment.isEmpty) this else Segment(segment, this)

    }

    /**
      * A path that ends with a '/' character.
      *
      * @param rest the remainder of the path (excluding the '/')
      */
    final case class Slash(rest: Path) extends Path {
      def isEmpty: Boolean           = false
      def isSlash: Boolean           = true
      def isSegment: Boolean         = false
      def asEmpty: Option[Empty]     = None
      def asSlash: Option[Slash]     = Some(this)
      def asSegment: Option[Segment] = None
      def asString: String           = rest.asString + "/"
      def pctEncoded: String         = rest.pctEncoded + "/"
      def startWithSlash: Boolean    = if (rest.isEmpty) true else rest.startWithSlash
      def prepend(other: Path): Path = Slash(rest prepend other)
      def +(segment: String): Path   = if (segment.isEmpty) this else Segment(segment, this)
    }

    /**
      * A path that ends with a segment.
      *
      * @param rest the remainder of the path (excluding the segment denoted by this)
      */
    final case class Segment private[rdf] (segment: String, rest: Path) extends Path {
      def isEmpty: Boolean           = false
      def isSlash: Boolean           = false
      def isSegment: Boolean         = true
      def asEmpty: Option[Empty]     = None
      def asSlash: Option[Slash]     = None
      def asSegment: Option[Segment] = Some(this)
      def asString: String           = rest.asString + segment
      def pctEncoded: String         = rest.pctEncoded + pctEncode(segment)
      def startWithSlash: Boolean    = rest.startWithSlash
      def prepend(other: Path): Path = (rest prepend other) + segment
      def +(s: String): Path         = if (segment.isEmpty) this else Segment(segment + s, rest)
    }

    final implicit val pathShow: Show[Path] = Show.show(_.asString)

    final implicit val pathEq: Eq[Path] = Eq.fromUniversalEquals
  }

  /**
    * Query part of an Iri as defined in RFC 3987.
    *
    * @param value a sorted multi map that represents all key -> value pairs
    */
  final case class Query private[rdf] (value: SortedMap[String, SortedSet[String]]) {

    /**
      * @return the UTF-8 representation of this Query
      */
    def asString: String =
      value
        .map {
          case (k, s) =>
            s.map {
                case v if v.isEmpty => k
                case v              => s"$k=$v"
              }
              .mkString("&")
        }
        .mkString("&")

    /**
      * @return the string representation using percent-encoding for any character that
      *         is not contained in the Set ''pchar''
      */
    def pctEncoded: String =
      value
        .map {
          case (k, s) =>
            s.map {
                case v if v.isEmpty => pctEncode(k)
                case v              => s"${pctEncode(k)}=${pctEncode(v)}"
              }
              .mkString("&")
        }
        .mkString("&")
  }

  object Query {

    /**
      * Attempts to parse the argument string as an `iquery` as defined by RFC 3987 and evaluate the key=value pairs.
      *
      * @param string the string to parse as a Query
      * @return Right(Query) if the parsing succeeds, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Query] =
      new IriParser(string).parseQuery

    final implicit val queryShow: Show[Query] = Show.show(_.asString)

    final implicit val queryEq: Eq[Query] =
      Eq.fromUniversalEquals
  }

  /**
    * Fragment part of an Iri as defined by RFC 3987.
    *
    * @param value the string value of the fragment
    */
  final case class Fragment private[rdf] (value: String) extends PctString

  object Fragment {

    /**
      * Attempts to parse the argument string as an `ifragment` as defined by RFC 3987.
      *
      * @param string the string to parse as a Fragment
      * @return Right(Fragment) if the parsing succeeds, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Fragment] =
      new IriParser(string).parseFragment

    final implicit val fragmentShow: Show[Fragment] = Show.show(_.asString)
    final implicit val fragmentEq: Eq[Fragment]     = Eq.fromUniversalEquals
  }

  /**
    * NID part of an Urn as defined by RFC 8141.
    *
    * @param value the string value of the fragment
    */
  final case class Nid private[rdf] (value: String) extends PctString

  object Nid {

    /**
      * Attempts to parse the argument string as a `NID` as defined by RFC 8141.
      *
      * @param string the string to parse as a NID
      * @return Right(NID) if the parsing succeeds, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Nid] =
      new IriParser(string).parseNid

    final implicit val nidShow: Show[Nid] = Show.show(_.asString)
    final implicit val nidEq: Eq[Nid]     = Eq.fromUniversalEquals
  }

  /**
    * Urn R or Q component as defined by RFC 8141.
    */
  final case class Component private[rdf] (value: String) extends PctString

  object Component {

    /**
      * Attempts to parse the argument string as a Urn R or Q component as defined by RFC 8141, but with the character
      * restrictions of RFC 3897.
      *
      * @param string the string to parse as a URN component
      * @return Right(Component) if the parsing succeeds, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Component] =
      new IriParser(string).parseComponent

    final implicit val componentShow: Show[Component] = Show.show(_.asString)
    final implicit val componentEq: Eq[Component]     = Eq.fromUniversalEquals
  }

  /**
    * An urn as defined by RFC 8141.
    *
    * @param nid      the namespace identifier
    * @param nss      the namespace specific string
    * @param r        the r component of the urn
    * @param q        the q component of the urn
    * @param fragment the f component of the urn, also known as fragment
    */
  final case class Urn(nid: Nid, nss: Path, r: Option[Component], q: Option[Query], fragment: Option[Fragment])
      extends AbsoluteIri {
    override def isUrl: Boolean     = false
    override def asUrl: Option[Url] = None
    override def isUrn: Boolean     = true
    override def asUrn: Option[Urn] = Some(this)
    override def +(segment: String): AbsoluteIri =
      if (nss.endsWithSlash) copy(nss = nss + segment) else copy(nss = nss / segment)

    override lazy val asString: String = {
      val rstr = r.map("?+" + _.asString).getOrElse("")
      val qstr = q.map("?=" + _.asString).getOrElse("")
      val f    = fragment.map("#" + _.asString).getOrElse("")
      s"urn:${nid.asString}:${nss.asString}$rstr$qstr$f"
    }

    override lazy val asUri: String = {
      val rstr = r.map("?+" + _.pctEncoded).getOrElse("")
      val qstr = q.map("?=" + _.pctEncoded).getOrElse("")
      val f    = fragment.map("#" + _.pctEncoded).getOrElse("")
      s"urn:${nid.pctEncoded}:${nss.pctEncoded}$rstr$qstr$f"
    }

  }

  object Urn {

    /**
      * Attempt to construct a new Urn from the argument validating the structure and the character encodings as per
      * RFC 3987 and 8141.
      *
      * @param string the string to parse as an Urn.
      * @return Right(urn) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Urn] =
      new IriParser(string).parseUrn

    final implicit val urnShow: Show[Urn] = Show.show(_.asUri)

    final implicit val urnEq: Eq[Urn] = Eq.fromUniversalEquals
  }

  final implicit val iriEq: Eq[Iri] = Eq.fromUniversalEquals
  final implicit def iriShow(implicit urnShow: Show[Urn], urlShow: Show[Url], relShow: Show[RelativeIri]): Show[Iri] =
    Show.show {
      case r: RelativeIri => r.show
      case url: Url       => url.show
      case urn: Urn       => urn.show
    }
}
