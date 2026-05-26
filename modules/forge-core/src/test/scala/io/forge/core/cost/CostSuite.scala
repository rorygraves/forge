package io.forge.core.cost

import upickle.default.{read, write}

/** PR-B B7 — codec round-trip for `Cost` and `CostTotals`. */
class CostSuite extends munit.FunSuite:

  test("Cost — round-trip with BigDecimal usd"):
    val c = Cost("anthropic", "claude-opus-4-7", 1234L, 567L, BigDecimal("0.0125"))
    val json = write(c)
    val parsed = read[Cost](json)
    assertEquals(parsed, c)

  test("CostTotals.zero is all-zero"):
    assertEquals(CostTotals.zero, CostTotals(BigDecimal(0), BigDecimal(0), BigDecimal(0)))

  test("CostTotals — round-trip non-zero values"):
    val totals = CostTotals(BigDecimal("1.50"), BigDecimal("0.25"), BigDecimal("0.10"))
    val json = write(totals)
    val parsed = read[CostTotals](json)
    assertEquals(parsed, totals)
