package ch.epfl.bluebrain.nexus.rdf.syntax

import java.util.UUID

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.syntax.jena._
import ch.epfl.bluebrain.nexus.rdf.syntax.node._
import ch.epfl.bluebrain.nexus.rdf.syntax.node.unsafe._
import ch.epfl.bluebrain.nexus.rdf.{Graph, GraphConfiguration}
import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.rdf.model
import org.apache.jena.rdf.model.{Model, ModelFactory, ResourceFactory}
import org.scalatest.{EitherValues, Inspectors, Matchers, WordSpecLike}

class JenaSyntaxSpec extends WordSpecLike with Matchers with Inspectors with EitherValues {

  "Jena syntax" should {

    "convert string literal to Jena model" in {
      (Literal("testLiteral"): model.Literal) shouldEqual ResourceFactory.createStringLiteral("testLiteral")
    }

    "convert typed literal to Jena model" in {
      val jenaLiteral: model.Literal = Literal("1999-04-09T20:00Z", url"http://schema.org/Date".value)
      jenaLiteral.getLexicalForm shouldEqual "1999-04-09T20:00Z"
      jenaLiteral.getDatatypeURI shouldEqual "http://schema.org/Date"
    }

    "convert literal with lang to Jena model" in {
      (Literal("bonjour", LanguageTag("fr").toOption.get): model.Literal) shouldEqual ResourceFactory.createLangLiteral(
        "bonjour",
        "fr")
    }

    "convert IRI to Jena resource" in {
      (url"http://nexus.example.com/example-uri": model.Resource) shouldEqual ResourceFactory.createResource(
        "http://nexus.example.com/example-uri")
    }

    "convert blank node to Jena model" in {
      val id = UUID.randomUUID().toString
      (b"$id": model.Resource).getId.getLabelString shouldEqual id
    }

    "convert property to Jena model" in {
      (url"http://nexus.example.com/example-property": model.Property) shouldEqual ResourceFactory.createProperty(
        "http://nexus.example.com/example-property")
    }

    // format: off
    "convert Graph to Jena Model" in {
      val graph: Model = Graph(
        (url"http://nexus.example.com/john-doe", url"http://schema.org/name",                           "John Doe"),
        (url"http://nexus.example.com/john-doe", url"http://schema.org/birthDate",                      Literal("1999-04-09T20:00Z", url"http://schema.org/Date".value)),
        (url"http://nexus.example.com/john-doe", url"http://schema.org/birth",                      Literal("2002-05-30T09:00:00", url"http://www.w3.org/2001/XMLSchema#dateTime".value)),
        (url"http://nexus.example.com/john-doe", url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type",  url"http://schema.org/Person")
      ).asJenaModel
      val model = ModelFactory.createDefaultModel()
      model.read(getClass.getResourceAsStream("/simple-model.json"), "http://nexus.example.com/", "JSONLD")

      graph.asGraph.right.value.triples shouldEqual model.asGraph.right.value.triples
    }
    // format: on

    "convert string literal from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      jenaToLiteral(ResourceFactory.createStringLiteral("testLiteral")).right.value shouldEqual Literal("testLiteral")
    }

    "convert string literal to xsd:date from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      val list            = List("2002-09-24", "2002-09-24Z", "2002-09-24-06:00", "2002-09-24+06:00")
      forAll(list) { date =>
        jenaToLiteral(ResourceFactory.createStringLiteral(date)).right.value shouldEqual Literal(date, xsd.date.value)
      }
    }

    "convert string literal to xsd:dateTime from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      val list = List("2002-05-30T09:00:00",
                      "2002-05-30T09:30:10.5",
                      "2002-05-30T09:30:10Z",
                      "2002-05-30T09:30:10-06:00",
                      "2002-05-30T09:30:10+06:00")
      forAll(list) { dateTime =>
        jenaToLiteral(ResourceFactory.createStringLiteral(dateTime)).right.value shouldEqual Literal(dateTime,
                                                                                                     xsd.dateTime.value)
      }
    }

    "convert string literal to xsd:time from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      val list            = List("09:30:10.5", "09:00:00", "09:30:10Z", "09:30:10-06:00", "09:30:10+06:00")
      forAll(list) { time =>
        jenaToLiteral(ResourceFactory.createStringLiteral(time)).right.value shouldEqual Literal(time, xsd.time.value)
      }
    }

    "do not convert to string literal when castDateTypes = false" in {
      implicit val config = GraphConfiguration(castDateTypes = false)
      val list            = List("09:30:10.5", "09:00:00", "09:30:10Z", "09:30:10-06:00", "09:30:10+06:00")
      forAll(list) { time =>
        jenaToLiteral(ResourceFactory.createStringLiteral(time)).right.value shouldEqual Literal(time)
      }
    }

    "convert typed literal from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      val convertedLiteral =
        jenaToLiteral(ResourceFactory.createTypedLiteral("1999-04-09T20:00Z",
                                                         new BaseDatatype("http://schema.org/Date"))).right.value
      convertedLiteral shouldEqual Literal("1999-04-09T20:00Z", url"http://schema.org/Date".value)
    }

    "convert literal with lang from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      jenaToLiteral(ResourceFactory.createLangLiteral("bonjour", "fr")).right.value shouldEqual
        Literal("bonjour", LanguageTag("fr").toOption.get)
    }

    "convert IRI from Jena resource" in {
      toIriOrBNode(ResourceFactory.createResource("http://nexus.example.com/example-uri")).right.value shouldEqual url"http://nexus.example.com/example-uri"
    }

    "fail to convert wrong IRI from Jena resource" in {
      toIriOrBNode(ResourceFactory.createResource("file:///some/path with space")).left.value should startWith(
        "'file:///some/path with space' could not be converted to Iri.")
    }

    "convert blank node from Jena model" in {
      val jenaResource = ResourceFactory.createResource()
      val id           = jenaResource.getId.getLabelString

      toIriOrBNode(jenaResource).right.value shouldEqual b"$id"
    }

    "convert property from Jena model" in {
      propToIriNode(ResourceFactory.createProperty("http://nexus.example.com/example-property")).right.value shouldEqual url"http://nexus.example.com/example-property"
    }

    "fail to convert wrong property from Jena model" in {
      propToIriNode(ResourceFactory.createProperty("file:///some/path with space")).left.value should startWith(
        "'file:///some/path with space' could not be converted to Iri.")
    }

    "convert Jena Model to Graph" in {
      val model = ModelFactory.createDefaultModel()
      model.read(getClass.getResourceAsStream("/simple-model.json"), "http://nexus.example.com/", "JSONLD")

      // format: off
      model.asGraph.right.value.triples shouldEqual Set[Graph.Triple](
        (url"http://nexus.example.com/john-doe", url"http://schema.org/name",                           "John Doe"),
        (url"http://nexus.example.com/john-doe", url"http://schema.org/birthDate",                      Literal("1999-04-09T20:00Z", url"http://schema.org/Date".value)),
        (url"http://nexus.example.com/john-doe", url"http://schema.org/birth",                      Literal("2002-05-30T09:00:00", url"http://www.w3.org/2001/XMLSchema#dateTime".value)),
        (url"http://nexus.example.com/john-doe", url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type",  url"http://schema.org/Person")
      )
      // format: off
    }

  }

}
