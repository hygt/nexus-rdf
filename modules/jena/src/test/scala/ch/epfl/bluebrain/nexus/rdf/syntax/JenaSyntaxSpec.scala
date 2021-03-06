package ch.epfl.bluebrain.nexus.rdf.syntax

import java.util.UUID

import ch.epfl.bluebrain.nexus.rdf.{Graph, GraphConfiguration}
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Node.{IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.syntax.jena._
import ch.epfl.bluebrain.nexus.rdf.syntax.node._
import ch.epfl.bluebrain.nexus.rdf.syntax.node.unsafe._
import org.apache.jena.datatypes.BaseDatatype
import org.apache.jena.rdf.model
import org.apache.jena.rdf.model.{Model, ModelFactory, ResourceFactory}
import org.scalatest.{Inspectors, Matchers, WordSpecLike}

class JenaSyntaxSpec extends WordSpecLike with Matchers with Inspectors {

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

      graph.asGraph.triples shouldEqual model.asGraph.triples
    }
    // format: on

    "convert string literal from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      (ResourceFactory.createStringLiteral("testLiteral"): Literal) shouldEqual Literal("testLiteral")
    }

    "convert string literal to xsd:date from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      val list            = List("2002-09-24", "2002-09-24Z", "2002-09-24-06:00", "2002-09-24+06:00")
      forAll(list) { date =>
        (ResourceFactory.createStringLiteral(date): Literal) shouldEqual Literal(date, xsd.date.value)
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
        (ResourceFactory.createStringLiteral(dateTime): Literal) shouldEqual Literal(dateTime, xsd.dateTime.value)
      }
    }

    "convert string literal to xsd:time from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      val list            = List("09:30:10.5", "09:00:00", "09:30:10Z", "09:30:10-06:00", "09:30:10+06:00")
      forAll(list) { time =>
        (ResourceFactory.createStringLiteral(time): Literal) shouldEqual Literal(time, xsd.time.value)
      }
    }

    "do not convert to string literal when castDateTypes = false" in {
      implicit val config = GraphConfiguration(castDateTypes = false)
      val list            = List("09:30:10.5", "09:00:00", "09:30:10Z", "09:30:10-06:00", "09:30:10+06:00")
      forAll(list) { time =>
        (ResourceFactory.createStringLiteral(time): Literal) shouldEqual Literal(time)
      }
    }

    "convert typed literal from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      val convertedLiteral: Literal =
        ResourceFactory.createTypedLiteral("1999-04-09T20:00Z", new BaseDatatype("http://schema.org/Date"))
      convertedLiteral shouldEqual Literal("1999-04-09T20:00Z", url"http://schema.org/Date".value)
    }

    "convert literal with lang from Jena model" in {
      implicit val config = GraphConfiguration(castDateTypes = true)
      (ResourceFactory.createLangLiteral("bonjour", "fr"): Literal) shouldEqual Literal("bonjour",
                                                                                        LanguageTag("fr").toOption.get)
    }

    "convert IRI from Jena resource" in {
      (ResourceFactory.createResource("http://nexus.example.com/example-uri"): IriOrBNode) shouldEqual url"http://nexus.example.com/example-uri"
    }

    "convert blank node from Jena model" in {
      val jenaResource = ResourceFactory.createResource()
      val id           = jenaResource.getId.getLabelString

      (jenaResource: IriOrBNode) shouldEqual b"$id"
    }

    "convert property from Jena model" in {
      (ResourceFactory.createProperty("http://nexus.example.com/example-property"): IriNode) shouldEqual url"http://nexus.example.com/example-property"
    }

    "convert Jena Model to Graph" in {
      val model = ModelFactory.createDefaultModel()
      model.read(getClass.getResourceAsStream("/simple-model.json"), "http://nexus.example.com/", "JSONLD")

      // format: off
      model.asGraph.triples shouldEqual Set[Graph.Triple](
        (url"http://nexus.example.com/john-doe", url"http://schema.org/name",                           "John Doe"),
        (url"http://nexus.example.com/john-doe", url"http://schema.org/birthDate",                      Literal("1999-04-09T20:00Z", url"http://schema.org/Date".value)),
        (url"http://nexus.example.com/john-doe", url"http://schema.org/birth",                      Literal("2002-05-30T09:00:00", url"http://www.w3.org/2001/XMLSchema#dateTime".value)),
        (url"http://nexus.example.com/john-doe", url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type",  url"http://schema.org/Person")
      )
      // format: off
    }

  }

}
