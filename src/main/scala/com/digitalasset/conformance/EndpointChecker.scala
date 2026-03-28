package com.digitalasset.conformance

import sttp.client3._
import java.time.Instant
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

/** Checks endpoint reachability and readiness with retry logic. */
class EndpointChecker(
  timeoutSeconds: Int = 30,
  maxRetries: Int = 5,
  retryDelaySeconds: Int = 3,
  verbose: Boolean = false
) {

  private val backend = HttpClientSyncBackend()

  def checkAll(endpoints: List[EndpointSpec]): List[EndpointResult] =
    endpoints.map(check)

  def check(spec: EndpointSpec): EndpointResult = {
    var lastResult: EndpointResult = null
    var attempt = 0

    while (attempt < maxRetries) {
      attempt += 1
      if (verbose && attempt > 1) {
        println(s"    Retry $attempt/${maxRetries} for ${spec.name}...")
      }

      lastResult = singleCheck(spec, attempt)

      if (lastResult.reachable && lastResult.statusMatch) {
        val symbol = "\u2713"
        println(s"  [$symbol] ${spec.name}: ${lastResult.statusCode.getOrElse("?")} " +
          s"(${lastResult.responseTimeMs}ms, attempt $attempt)")
        return lastResult
      }

      if (attempt < maxRetries) {
        Thread.sleep(retryDelaySeconds * 1000L)
      }
    }

    val symbol = "\u2717"
    val detail = lastResult.error.getOrElse(
      s"status=${lastResult.statusCode.getOrElse("none")}, expected=${spec.expectedStatus}"
    )
    println(s"  [$symbol] ${spec.name}: FAILED — $detail (after $attempt attempts)")
    lastResult
  }

  private def singleCheck(spec: EndpointSpec, attempt: Int): EndpointResult = {
    val start = System.currentTimeMillis()
    val timestamp = Instant.now()

    Try {
      val request = basicRequest
        .get(uri"${spec.url}")
        .readTimeout(timeoutSeconds.seconds)
        .response(asStringAlways)

      val response = request.send(backend)
      val elapsed = System.currentTimeMillis() - start

      EndpointResult(
        name = spec.name,
        url = spec.url,
        category = spec.category,
        required = spec.required,
        reachable = true,
        statusCode = Some(response.code.code),
        expectedStatus = spec.expectedStatus,
        statusMatch = response.code.code == spec.expectedStatus,
        responseTimeMs = elapsed,
        responseBody = Some(truncate(response.body, 1000)),
        error = None,
        timestamp = timestamp,
        attempts = attempt
      )
    } match {
      case Success(result) => result
      case Failure(ex) =>
        val elapsed = System.currentTimeMillis() - start
        EndpointResult(
          name = spec.name,
          url = spec.url,
          category = spec.category,
          required = spec.required,
          reachable = false,
          statusCode = None,
          expectedStatus = spec.expectedStatus,
          statusMatch = false,
          responseTimeMs = elapsed,
          responseBody = None,
          error = Some(s"${ex.getClass.getSimpleName}: ${ex.getMessage}"),
          timestamp = timestamp,
          attempts = attempt
        )
    }
  }

  private def truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max) + "...[truncated]"

  def close(): Unit = backend.close()
}
