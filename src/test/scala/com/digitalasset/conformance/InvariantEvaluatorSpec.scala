package com.digitalasset.conformance

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class InvariantEvaluatorSpec extends AnyFlatSpec with Matchers {

  private val now = Instant.now()

  private def makeResult(
      name: String,
      reachable: Boolean = true,
      statusCode: Option[Int] = Some(200),
      expectedStatus: Int = 200,
      category: String = "readiness",
      required: Boolean = true,
      error: Option[String] = None
  ): EndpointResult = EndpointResult(
    name = name,
    url = s"http://localhost/$name",
    category = category,
    required = required,
    reachable = reachable,
    statusCode = statusCode,
    expectedStatus = expectedStatus,
    statusMatch = statusCode.contains(expectedStatus),
    responseTimeMs = 42,
    responseBody = Some("ok"),
    error = error,
    timestamp = now,
    attempts = 1
  )

  "InvariantEvaluator" should "pass all_endpoints_reachable when all required are reachable" in {
    val results   = List(makeResult("a"), makeResult("b"))
    val inv       = InvariantSpec("INV-001", "desc", "all_endpoints_reachable")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe true
  }

  it should "fail all_endpoints_reachable when a required endpoint is unreachable" in {
    val results   = List(makeResult("a"), makeResult("b", reachable = false, statusCode = None))
    val inv       = InvariantSpec("INV-001", "desc", "all_endpoints_reachable")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe false
    evaluated.head.detail.get should include("b")
  }

  it should "pass all_endpoints_reachable when only optional endpoints are unreachable" in {
    val results = List(
      makeResult("a"),
      makeResult("b", reachable = false, statusCode = None, required = false)
    )
    val inv       = InvariantSpec("INV-001", "desc", "all_endpoints_reachable")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe true
  }

  it should "pass all_readiness_pass when all readiness endpoints return expected status" in {
    val results = List(
      makeResult("r1", category = "readiness"),
      makeResult("r2", category = "readiness")
    )
    val inv       = InvariantSpec("INV-002", "desc", "all_readiness_pass")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe true
  }

  it should "fail all_readiness_pass when a readiness endpoint has wrong status" in {
    val results = List(
      makeResult("r1", category = "readiness"),
      makeResult("r2", category = "readiness", statusCode = Some(503))
    )
    val inv       = InvariantSpec("INV-002", "desc", "all_readiness_pass")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe false
  }

  it should "fail all_readiness_pass when a readiness endpoint is unreachable" in {
    val results = List(
      makeResult("r1", category = "readiness"),
      makeResult("r2", category = "readiness", reachable = false, statusCode = None)
    )
    val inv       = InvariantSpec("INV-002", "desc", "all_readiness_pass")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe false
  }

  it should "fail all_readiness_pass when no readiness endpoints exist" in {
    val results   = List(makeResult("a", category = "admin"))
    val inv       = InvariantSpec("INV-002", "desc", "all_readiness_pass")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe false
    evaluated.head.detail.get should include("No readiness")
  }

  it should "pass deterministic_results when all results are unique and timestamped" in {
    val results   = List(makeResult("a"), makeResult("b"))
    val inv       = InvariantSpec("INV-003", "desc", "deterministic_results")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe true
  }

  it should "fail deterministic_results when endpoint names are duplicated" in {
    val results   = List(makeResult("a"), makeResult("a"))
    val inv       = InvariantSpec("INV-003", "desc", "deterministic_results")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe false
    evaluated.head.detail.get should include("Duplicate")
  }

  it should "fail deterministic_results when results list is empty" in {
    val inv       = InvariantSpec("INV-003", "desc", "deterministic_results")
    val evaluated = InvariantEvaluator.evaluate(List(inv), Nil)
    evaluated.head.passed shouldBe false
    evaluated.head.detail.get should include("No results")
  }

  it should "pass failures_identify_endpoint when failures have proper details" in {
    val results = List(
      makeResult("a"),
      makeResult("b", reachable = false, statusCode = None, error = Some("Connection refused"))
    )
    val inv       = InvariantSpec("INV-004", "desc", "failures_identify_endpoint")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe true
  }

  it should "pass failures_identify_endpoint when no failures exist" in {
    val results   = List(makeResult("a"), makeResult("b"))
    val inv       = InvariantSpec("INV-004", "desc", "failures_identify_endpoint")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe true
    evaluated.head.detail.get should include("No failures")
  }

  it should "fail failures_identify_endpoint when a failure lacks error detail" in {
    val results = List(
      makeResult("a"),
      makeResult("b", reachable = false, statusCode = None, error = None).copy(name = "", url = "")
    )
    val inv       = InvariantSpec("INV-004", "desc", "failures_identify_endpoint")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe false
    evaluated.head.detail.get should include("lack identification")
  }

  it should "pass report_reflects_run when results exist" in {
    val results   = List(makeResult("a"))
    val inv       = InvariantSpec("INV-005", "desc", "report_reflects_run")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe true
  }

  it should "fail report_reflects_run when results are empty" in {
    val inv       = InvariantSpec("INV-005", "desc", "report_reflects_run")
    val evaluated = InvariantEvaluator.evaluate(List(inv), Nil)
    evaluated.head.passed shouldBe false
    evaluated.head.detail.get should include("No endpoint results")
  }

  it should "handle unknown check types gracefully" in {
    val results   = List(makeResult("a"))
    val inv       = InvariantSpec("INV-999", "desc", "unknown_check")
    val evaluated = InvariantEvaluator.evaluate(List(inv), results)
    evaluated.head.passed shouldBe false
    evaluated.head.detail.get should include("Unknown")
  }
}
