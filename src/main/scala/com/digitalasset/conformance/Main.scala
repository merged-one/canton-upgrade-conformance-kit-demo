package com.digitalasset.conformance

import scopt.OParser
import java.nio.file.{Path, Paths}

/** CLI entrypoint for the Canton Conformance Kit. */
object Main {

  case class CliConfig(
    scenario: Path = Paths.get("demo/scenarios/localnet-readiness.yaml"),
    outputDir: Path = Paths.get("reports/latest"),
    timeout: Int = 30,
    retries: Int = 5,
    retryDelay: Int = 3,
    verbose: Boolean = false
  )

  private val builder = OParser.builder[CliConfig]
  private val parser = {
    import builder._
    OParser.sequence(
      programName("canton-conformance-kit"),
      head("Canton Conformance Kit", "0.1.0"),
      opt[String]('s', "scenario")
        .action((x, c) => c.copy(scenario = Paths.get(x)))
        .text("Path to scenario YAML file"),
      opt[String]('o', "output")
        .action((x, c) => c.copy(outputDir = Paths.get(x)))
        .text("Output directory for reports"),
      opt[Int]('t', "timeout")
        .action((x, c) => c.copy(timeout = x))
        .text("HTTP request timeout in seconds (default: 30)"),
      opt[Int]('r', "retries")
        .action((x, c) => c.copy(retries = x))
        .text("Number of retries per endpoint (default: 5)"),
      opt[Int]("retry-delay")
        .action((x, c) => c.copy(retryDelay = x))
        .text("Delay between retries in seconds (default: 3)"),
      opt[Unit]('v', "verbose")
        .action((_, c) => c.copy(verbose = true))
        .text("Enable verbose output"),
      help("help").text("Print this usage text")
    )
  }

  def main(args: Array[String]): Unit = {
    OParser.parse(parser, args, CliConfig()) match {
      case Some(config) => run(config)
      case None         => System.exit(1)
    }
  }

  private def run(config: CliConfig): Unit = {
    println("=" * 60)
    println(" Canton Conformance Kit — Live Environment Qualification")
    println("=" * 60)
    println()

    // Load scenario
    println(s"[1/4] Loading scenario: ${config.scenario}")
    val scenario = ScenarioLoader.load(config.scenario) match {
      case Right(s) => s
      case Left(err) =>
        System.err.println(s"ERROR: Failed to load scenario: $err")
        System.exit(1)
        throw new RuntimeException("unreachable")
    }
    println(s"  Scenario: ${scenario.name}")
    println(s"  Endpoints: ${scenario.endpoints.size}")
    println(s"  Invariants: ${scenario.invariants.size}")
    println()

    // Execute checks
    println("[2/4] Executing endpoint checks...")
    val checker = new EndpointChecker(
      timeoutSeconds = config.timeout,
      maxRetries = config.retries,
      retryDelaySeconds = config.retryDelay,
      verbose = config.verbose
    )
    val results = checker.checkAll(scenario.endpoints)
    println()

    // Evaluate invariants
    println("[3/4] Evaluating invariants...")
    val invariantResults = InvariantEvaluator.evaluate(scenario.invariants, results)
    invariantResults.foreach { inv =>
      val status = if (inv.passed) "PASS" else "FAIL"
      println(s"  [$status] ${inv.id}: ${inv.description}")
      if (!inv.passed) inv.detail.foreach(d => println(s"         Detail: $d"))
    }
    println()

    // Generate reports
    println(s"[4/4] Generating reports to ${config.outputDir}/")
    val report = ConformanceReport(
      scenario = scenario,
      endpointResults = results,
      invariantResults = invariantResults
    )

    val outputDir = config.outputDir
    outputDir.toFile.mkdirs()
    ReportWriter.writeJson(report, outputDir.resolve("conformance-report.json"))
    ReportWriter.writeMarkdown(report, outputDir.resolve("conformance-report.md"))
    println()

    // Summary
    val allPassed = invariantResults.forall(_.passed)
    val passCount = invariantResults.count(_.passed)
    val totalCount = invariantResults.size
    println("=" * 60)
    if (allPassed) {
      println(s" CONFORMANCE RESULT: PASS ($passCount/$totalCount invariants)")
    } else {
      println(s" CONFORMANCE RESULT: FAIL ($passCount/$totalCount invariants passed)")
    }
    println("=" * 60)
    println()
    println(s"  JSON report:     ${outputDir.resolve("conformance-report.json")}")
    println(s"  Markdown report: ${outputDir.resolve("conformance-report.md")}")
    println()

    if (!allPassed) System.exit(1)
  }
}
