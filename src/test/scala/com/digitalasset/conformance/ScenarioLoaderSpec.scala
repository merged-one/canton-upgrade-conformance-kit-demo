package com.digitalasset.conformance

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScenarioLoaderSpec extends AnyFlatSpec with Matchers {

  val validYaml: String =
    """scenario:
      |  name: test-scenario
      |  description: A test scenario
      |  version: "1.0"
      |  endpoints:
      |    - name: validator-1-readiness
      |      url: http://localhost:2903/api/validator/readyz
      |      expected_status: 200
      |      category: readiness
      |      required: true
      |    - name: validator-2-readiness
      |      url: http://localhost:3903/api/validator/readyz
      |      expected_status: 200
      |      category: readiness
      |      required: true
      |  invariants:
      |    - id: INV-001
      |      description: All required endpoints must be reachable
      |      check: all_endpoints_reachable
      |    - id: INV-002
      |      description: All readiness checks must pass
      |      check: all_readiness_pass
      |""".stripMargin

  "ScenarioLoader" should "parse a valid YAML scenario" in {
    val result = ScenarioLoader.loadFromString(validYaml)
    result shouldBe a[Right[_, _]]

    val scenario = result.toOption.get
    scenario.name shouldBe "test-scenario"
    scenario.description shouldBe "A test scenario"
    scenario.version shouldBe "1.0"
    scenario.endpoints should have size 2
    scenario.invariants should have size 2
  }

  it should "parse endpoint properties correctly" in {
    val scenario = ScenarioLoader.loadFromString(validYaml).toOption.get
    val ep = scenario.endpoints.head
    ep.name shouldBe "validator-1-readiness"
    ep.url shouldBe "http://localhost:2903/api/validator/readyz"
    ep.expectedStatus shouldBe 200
    ep.category shouldBe "readiness"
    ep.required shouldBe true
  }

  it should "parse invariant properties correctly" in {
    val scenario = ScenarioLoader.loadFromString(validYaml).toOption.get
    val inv = scenario.invariants.head
    inv.id shouldBe "INV-001"
    inv.description shouldBe "All required endpoints must be reachable"
    inv.check shouldBe "all_endpoints_reachable"
  }

  it should "return an error for missing scenario key" in {
    val result = ScenarioLoader.loadFromString("foo: bar")
    result shouldBe a[Left[_, _]]
    result.left.toOption.get should include("Missing")
  }

  it should "handle missing optional fields with defaults" in {
    val minimal =
      """scenario:
        |  name: minimal
        |  endpoints: []
        |  invariants: []
        |""".stripMargin
    val result = ScenarioLoader.loadFromString(minimal)
    result shouldBe a[Right[_, _]]
    val s = result.toOption.get
    s.description shouldBe ""
    s.version shouldBe "0.1.0"
  }
}
