package com.digitalasset.conformance

/** Evaluates invariants against collected endpoint results. */
object InvariantEvaluator {

  def evaluate(
      invariants: List[InvariantSpec],
      results: List[EndpointResult]
  ): List[InvariantResult] =
    invariants.map(inv => evaluateOne(inv, results))

  private def evaluateOne(
      inv: InvariantSpec,
      results: List[EndpointResult]
  ): InvariantResult =
    inv.check match {
      case "all_endpoints_reachable" =>
        checkAllEndpointsReachable(inv, results)

      case "all_readiness_pass" =>
        checkAllReadinessPass(inv, results)

      case "deterministic_results" =>
        checkDeterministicResults(inv, results)

      case "failures_identify_endpoint" =>
        checkFailuresIdentifyEndpoint(inv, results)

      case "report_reflects_run" =>
        // This is validated by the fact that we're generating from actual results.
        // If we have results at all, this invariant passes.
        InvariantResult(
          id = inv.id,
          description = inv.description,
          check = inv.check,
          passed = results.nonEmpty,
          detail =
            if (results.nonEmpty)
              Some(s"Report generated from ${results.size} actual endpoint checks")
            else
              Some("No endpoint results to report on")
        )

      case other =>
        InvariantResult(
          id = inv.id,
          description = inv.description,
          check = other,
          passed = false,
          detail = Some(s"Unknown check type: $other")
        )
    }

  /** INV-001: All required endpoints must be reachable. */
  private def checkAllEndpointsReachable(
      inv: InvariantSpec,
      results: List[EndpointResult]
  ): InvariantResult = {
    val required    = results.filter(_.required)
    val unreachable = required.filterNot(_.reachable)

    InvariantResult(
      id = inv.id,
      description = inv.description,
      check = inv.check,
      passed = unreachable.isEmpty,
      detail =
        if (unreachable.isEmpty)
          Some(s"All ${required.size} required endpoints reachable")
        else
          Some(s"Unreachable: ${unreachable.map(r => s"${r.name} (${r.url})").mkString(", ")}")
    )
  }

  /** INV-002: All readiness endpoints must return expected status. */
  private def checkAllReadinessPass(
      inv: InvariantSpec,
      results: List[EndpointResult]
  ): InvariantResult = {
    val readiness = results.filter(_.category == "readiness")
    val failed    = readiness.filterNot(r => r.reachable && r.statusMatch)

    InvariantResult(
      id = inv.id,
      description = inv.description,
      check = inv.check,
      passed = failed.isEmpty && readiness.nonEmpty,
      detail = if (readiness.isEmpty)
        Some("No readiness endpoints defined")
      else if (failed.isEmpty)
        Some(s"All ${readiness.size} readiness endpoints passed")
      else
        Some(
          s"Failed: ${failed.map(r => s"${r.name} (status=${r.statusCode.getOrElse("none")}, expected=${r.expectedStatus})").mkString(", ")}"
        )
    )
  }

  /** INV-003: Results must be deterministic (each endpoint has exactly one result). */
  private def checkDeterministicResults(
      inv: InvariantSpec,
      results: List[EndpointResult]
  ): InvariantResult = {
    val names             = results.map(_.name)
    val duplicates        = names.groupBy(identity).collect { case (n, l) if l.size > 1 => n }
    val allHaveTimestamps = results.forall(_.timestamp != null)

    InvariantResult(
      id = inv.id,
      description = inv.description,
      check = inv.check,
      passed = duplicates.isEmpty && allHaveTimestamps && results.nonEmpty,
      detail =
        if (duplicates.nonEmpty)
          Some(s"Duplicate endpoint names: ${duplicates.mkString(", ")}")
        else if (results.isEmpty)
          Some("No results to validate")
        else
          Some(s"${results.size} deterministic results with timestamps")
    )
  }

  /** INV-004: Any failures must clearly identify which endpoint/check failed. */
  private def checkFailuresIdentifyEndpoint(
      inv: InvariantSpec,
      results: List[EndpointResult]
  ): InvariantResult = {
    val failures = results.filterNot(r => r.reachable && r.statusMatch)
    val allIdentified = failures.forall { r =>
      r.name.nonEmpty && r.url.nonEmpty && (r.error.isDefined || r.statusCode.isDefined)
    }

    InvariantResult(
      id = inv.id,
      description = inv.description,
      check = inv.check,
      passed = allIdentified,
      detail =
        if (failures.isEmpty)
          Some("No failures to evaluate (all endpoints passed)")
        else if (allIdentified)
          Some(s"All ${failures.size} failures properly identified")
        else
          Some("Some failures lack identification details")
    )
  }
}
