package com.digitalasset.conformance

import org.yaml.snakeyaml.Yaml
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.annotation.nowarn
import scala.util.Try

/** Loads scenario definitions from YAML files. */
object ScenarioLoader {

  def load(path: Path): Either[String, Scenario] =
    if (!Files.exists(path))
      Left(s"Scenario file not found: $path")
    else
      Try {
        val content                                     = new String(Files.readAllBytes(path), "UTF-8")
        val yaml                                        = new Yaml()
        @nowarn("cat=w-flag-dead-code") val raw: Object = yaml.load(content)
        val doc                                         = raw.asInstanceOf[java.util.Map[String, Any]]
        parseScenario(doc)
      }.toEither.left.map(e => s"YAML parse error: ${e.getMessage}").flatten

  def loadFromString(content: String): Either[String, Scenario] =
    Try {
      val yaml                                        = new Yaml()
      @nowarn("cat=w-flag-dead-code") val raw: Object = yaml.load(content)
      val doc                                         = raw.asInstanceOf[java.util.Map[String, Any]]
      parseScenario(doc)
    }.toEither.left.map(e => s"YAML parse error: ${e.getMessage}").flatten

  private def parseScenario(doc: java.util.Map[String, Any]): Either[String, Scenario] =
    Option(doc.get("scenario"))
      .collect { case m: java.util.Map[_, _] => m.asInstanceOf[java.util.Map[String, Any]] }
      .toRight("Missing top-level 'scenario' key")
      .map { scenarioMap =>
        val name        = getString(scenarioMap, "name").getOrElse("unnamed")
        val description = getString(scenarioMap, "description").getOrElse("")
        val version     = getString(scenarioMap, "version").getOrElse("0.1.0")

        val endpoints = getList(scenarioMap, "endpoints")
          .map { items =>
            items.flatMap {
              case m: java.util.Map[_, _] =>
                val em = m.asInstanceOf[java.util.Map[String, Any]]
                for {
                  endpointName <- getString(em, "name")
                  url          <- getString(em, "url")
                } yield EndpointSpec(
                  name = endpointName,
                  url = url,
                  expectedStatus = getInt(em, "expected_status").getOrElse(200),
                  category = getString(em, "category").getOrElse("readiness"),
                  required = getBool(em, "required").getOrElse(true)
                )
              case _ => None
            }
          }
          .getOrElse(Nil)

        val invariants = getList(scenarioMap, "invariants")
          .map { items =>
            items.flatMap {
              case m: java.util.Map[_, _] =>
                val im = m.asInstanceOf[java.util.Map[String, Any]]
                for {
                  id   <- getString(im, "id")
                  desc <- getString(im, "description")
                } yield InvariantSpec(
                  id = id,
                  description = desc,
                  check = getString(im, "check").getOrElse("all_endpoints_reachable")
                )
              case _ => None
            }
          }
          .getOrElse(Nil)

        Scenario(
          name = name,
          description = description,
          version = version,
          endpoints = endpoints,
          invariants = invariants
        )
      }

  private def getString(m: java.util.Map[String, Any], key: String): Option[String] =
    Option(m.get(key)).map(_.toString)

  private def getInt(m: java.util.Map[String, Any], key: String): Option[Int] =
    Option(m.get(key)).flatMap {
      case i: java.lang.Integer => Some(i.intValue())
      case s: String            => Try(s.toInt).toOption
      case _                    => None
    }

  private def getBool(m: java.util.Map[String, Any], key: String): Option[Boolean] =
    Option(m.get(key)).flatMap {
      case b: java.lang.Boolean => Some(b.booleanValue())
      case s: String            => Try(s.toBoolean).toOption
      case _                    => None
    }

  private def getList(m: java.util.Map[String, Any], key: String): Option[List[Any]] =
    Option(m.get(key)).collect { case l: java.util.List[_] =>
      l.asScala.toList
    }
}
