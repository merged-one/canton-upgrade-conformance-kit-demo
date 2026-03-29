.PHONY: fmt fmt-check compile test assembly ci integration integration-harness clean

# Format all Scala and sbt files
fmt:
	sbt scalafmtAll scalafmtSbt

# Check formatting without modifying files
fmt-check:
	sbt scalafmtCheckAll scalafmtSbtCheck

# Compile with warnings as errors
compile:
	sbt 'set Compile / scalacOptions += "-Xfatal-warnings"' compile

# Run unit tests with JUnit XML output
test:
	sbt 'set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", "target/test-reports")' test

# Build fat JAR
assembly:
	sbt assembly

# Run full CI pipeline locally
ci: fmt-check compile test assembly
	@echo "CI passed."

# Run full integration test: start Canton network, run conformance harness, generate reports
integration:
	./demo/run-live-demo.sh

# Run conformance harness only (requires Canton network already running)
integration-harness:
	sbt "run --scenario demo/scenarios/localnet-readiness.yaml --output reports/latest --retries 3 --retry-delay 5 --verbose"

# Clean build artifacts
clean:
	sbt clean
