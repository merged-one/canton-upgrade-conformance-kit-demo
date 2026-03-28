package com.digitalasset.conformance

import java.time.Instant

/** Scenario definition loaded from YAML. */
case class Scenario(
  name: String,
  description: String,
  version: String,
  endpoints: List[EndpointSpec],
  invariants: List[InvariantSpec]
)

/** A single endpoint to check. */
case class EndpointSpec(
  name: String,
  url: String,
  expectedStatus: Int,
  category: String,
  required: Boolean
)

/** An invariant to evaluate after checks complete. */
case class InvariantSpec(
  id: String,
  description: String,
  check: String
)

/** Result of checking a single endpoint. */
case class EndpointResult(
  name: String,
  url: String,
  category: String,
  required: Boolean,
  reachable: Boolean,
  statusCode: Option[Int],
  expectedStatus: Int,
  statusMatch: Boolean,
  responseTimeMs: Long,
  responseBody: Option[String],
  error: Option[String],
  timestamp: Instant,
  attempts: Int
)

/** Result of evaluating a single invariant. */
case class InvariantResult(
  id: String,
  description: String,
  check: String,
  passed: Boolean,
  detail: Option[String]
)

/** Complete conformance report. */
case class ConformanceReport(
  scenario: Scenario,
  endpointResults: List[EndpointResult],
  invariantResults: List[InvariantResult]
) {
  val overallPass: Boolean = invariantResults.forall(_.passed)
  val timestamp: Instant = Instant.now()
}
