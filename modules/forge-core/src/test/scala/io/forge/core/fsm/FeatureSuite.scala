package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.cost.CostTotals
import io.forge.core.manifest.Manifest

import upickle.default.{read, write}

/** PR-B B7 — codec round-trip for `Feature`. */
class FeatureSuite extends munit.FunSuite:

  private def loadManifest(): Manifest =
    val stream = getClass.getResourceAsStream("/manifest-fixture.json")
    assert(stream != null, "manifest-fixture.json not found on test classpath")
    try Manifest.fromJson(scala.io.Source.fromInputStream(stream).mkString)
    finally stream.close()

  test("Feature.initial — projections are empty and state is Drafting"):
    val m = loadManifest()
    val f = Feature.initial(m.featureId, m)
    assertEquals(f.state, FsmState.Drafting)
    assertEquals(f.cost, CostTotals.zero)
    assertEquals(f.designSessionId, None)
    assertEquals(f.currentPieceSessionId, None)
    assertEquals(f.branchProtectionCacheEpoch, 0L)

  test("Feature — round-trip with populated session ids and non-zero cost"):
    val m = loadManifest()
    val f = Feature(
      id = m.featureId,
      manifest = m,
      state = FsmState.InteractiveSpec,
      cost = CostTotals(BigDecimal("1.25"), BigDecimal("0.50"), BigDecimal("0.10")),
      designSessionId = Some("sess-abc"),
      currentPieceSessionId = None,
      branchProtectionCacheEpoch = 7L
    )
    val json = write(f)
    val parsed = read[Feature](json)
    assertEquals(parsed, f)
