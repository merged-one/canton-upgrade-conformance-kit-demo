package com.digitalasset.conformance

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.time.Instant

class ReportWriterSpec extends AnyFlatSpec with Matchers {

  private val now = Instant.now()

  private val scenario = Scenario(
    name = "test",
    description = "test scenario",
    version = "1.0",
    endpoints = List(
      EndpointSpec("ep1", "http://localhost:2903/readyz", 200, "readiness", required = true)
    ),
    invariants = List(
      InvariantSpec("INV-001", "all reachable", "all_endpoints_reachable")
    )
  )

  private val results = List(
    EndpointResult(
      name = "ep1",
      url = "http://localhost:2903/readyz",
      category = "readiness",
      required = true,
      reachable = true,
      statusCode = Some(200),
      expectedStatus = 200,
      statusMatch = true,
      responseTimeMs = 42,
      responseBody = Some("ok"),
      error = None,
      timestamp = now,
      attempts = 1
    )
  )

  private val invariantResults = List(
    InvariantResult("INV-001", "all reachable", "all_endpoints_reachable", passed = true, Some("All 1 required endpoints reachable"))
  )

  private val report = ConformanceReport(scenario, results, invariantResults)

  "ReportWriter" should "write a valid JSON report" in {
    val tmpDir = Files.createTempDirectory("conformance-test")
    val jsonPath = tmpDir.resolve("report.json")

    ReportWriter.writeJson(report, jsonPath)

    val content = new String(Files.readAllBytes(jsonPath))
    content should include("conformanceReport")
    content should include("PASS")
    content should include("ep1")
    content should include("INV-001")

    // Validate it's parseable JSON
    io.circe.parser.parse(content) shouldBe a[Right[_, _]]

    // Cleanup
    Files.delete(jsonPath)
    Files.delete(tmpDir)
  }

  it should "write a valid Markdown report" in {
    val tmpDir = Files.createTempDirectory("conformance-test")
    val mdPath = tmpDir.resolve("report.md")

    ReportWriter.writeMarkdown(report, mdPath)

    val content = new String(Files.readAllBytes(mdPath))
    content should include("# Canton Conformance Report")
    content should include("PASS")
    content should include("ep1")
    content should include("INV-001")
    content should include("Invariant Evaluation")

    Files.delete(mdPath)
    Files.delete(tmpDir)
  }
}
