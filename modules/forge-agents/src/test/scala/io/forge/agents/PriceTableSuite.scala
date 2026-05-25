package io.forge.agents

import upickle.default.{read, write}

class PriceTableSuite extends munit.FunSuite:

  private val gpt5codex = ModelPrice(
    inputPerMillionUsd = BigDecimal("1.25"),
    cachedInputPerMillionUsd = BigDecimal("0.125"),
    outputPerMillionUsd = BigDecimal("10.00"),
    reasoningOutputPerMillionUsd = BigDecimal("10.00")
  )

  private val table = PriceTable(
    schemaVersion = PriceTable.CurrentSchemaVersion,
    models = Map("gpt-5-codex" -> gpt5codex)
  )

  // --- usdFor ----

  test("usdFor returns None for an unknown model"):
    assertEquals(table.usdFor("gpt-future", CodexTokens(1000, 0, 1000, 0)), None)

  test("usdFor on the empty table is always None"):
    assertEquals(PriceTable.empty.usdFor("gpt-5-codex", CodexTokens(1, 0, 1, 0)), None)

  test("usdFor applies inputRate only to uncached input and outputRate only to non-reasoning output"):
    // cached = subset of input, reasoning = subset of output (OpenAI usage shape).
    // 1M input total with 0 cached, 1M output total with 0 reasoning → input + output rates only.
    val tokens = CodexTokens(1_000_000L, 0L, 1_000_000L, 0L)
    val usd = table.usdFor("gpt-5-codex", tokens).get
    assertEquals(usd, BigDecimal("1.25") + BigDecimal("10.00"))

  test("usdFor: full cached input is billed at the cached rate, not the input rate"):
    // input total = cached = 1M → all input billed at cachedRate ($0.125/M), zero at inputRate.
    val tokens = CodexTokens(1_000_000L, 1_000_000L, 0L, 0L)
    val usd = table.usdFor("gpt-5-codex", tokens).get
    assertEquals(usd, BigDecimal("0.125"))

  test("usdFor: full reasoning output is billed at the reasoning rate, not the output rate"):
    // output total = reasoning = 1M → all output billed at reasoningRate ($10/M), zero at outputRate.
    val tokens = CodexTokens(0L, 0L, 1_000_000L, 1_000_000L)
    val usd = table.usdFor("gpt-5-codex", tokens).get
    assertEquals(usd, BigDecimal("10.00"))

  test("usdFor: partial cached / reasoning splits add up to the per-bucket totals"):
    // input total = 1M with 200k cached → 800k at inputRate + 200k at cachedRate
    // output total = 1M with 300k reasoning → 700k at outputRate + 300k at reasoningRate
    val tokens = CodexTokens(1_000_000L, 200_000L, 1_000_000L, 300_000L)
    val usd = table.usdFor("gpt-5-codex", tokens).get
    val expected =
      (BigDecimal(800_000) * BigDecimal("1.25") +
        BigDecimal(200_000) * BigDecimal("0.125") +
        BigDecimal(700_000) * BigDecimal("10.00") +
        BigDecimal(300_000) * BigDecimal("10.00")) / BigDecimal(1_000_000)
    assertEquals(usd, expected)

  test("usdFor scales linearly with token counts"):
    val small = table.usdFor("gpt-5-codex", CodexTokens(1000, 0, 1000, 0)).get
    val big = table.usdFor("gpt-5-codex", CodexTokens(2000, 0, 2000, 0)).get
    assertEquals(big, small * 2)

  test("usdFor returns zero for an all-zero turn (an entry exists, no usage)"):
    assertEquals(table.usdFor("gpt-5-codex", CodexTokens(0, 0, 0, 0)), Some(BigDecimal(0)))

  test("usdFor returns None when cachedInputTokens exceeds inputTokens (invariant violation)"):
    assertEquals(table.usdFor("gpt-5-codex", CodexTokens(100L, 200L, 0L, 0L)), None)

  test("usdFor returns None when reasoningOutputTokens exceeds outputTokens (invariant violation)"):
    assertEquals(table.usdFor("gpt-5-codex", CodexTokens(0L, 0L, 100L, 200L)), None)

  test("usdFor returns None when inputTokens is negative"):
    assertEquals(table.usdFor("gpt-5-codex", CodexTokens(-1L, 0L, 0L, 0L)), None)

  test("usdFor returns None when cachedInputTokens is negative"):
    assertEquals(table.usdFor("gpt-5-codex", CodexTokens(0L, -1L, 0L, 0L)), None)

  test("usdFor returns None when outputTokens is negative"):
    assertEquals(table.usdFor("gpt-5-codex", CodexTokens(0L, 0L, -1L, 0L)), None)

  test("usdFor returns None when reasoningOutputTokens is negative"):
    assertEquals(table.usdFor("gpt-5-codex", CodexTokens(0L, 0L, 0L, -1L)), None)

  test("usdFor invariant check fires before the model lookup (unknown model + bad shape both → None)"):
    assertEquals(table.usdFor("gpt-future", CodexTokens(0L, 0L, 100L, 200L)), None)

  // --- codec ----

  test("PriceTable JSON round-trips through upickle"):
    val json = write(table)
    val decoded = read[PriceTable](json)
    assertEquals(decoded, table)

  test("PriceTable parses the §7.10(b) example shape verbatim"):
    val example =
      """{
        |  "schemaVersion": 1,
        |  "models": {
        |    "gpt-5-codex": {
        |      "inputPerMillionUsd": 1.25,
        |      "cachedInputPerMillionUsd": 0.125,
        |      "outputPerMillionUsd": 10.00,
        |      "reasoningOutputPerMillionUsd": 10.00
        |    }
        |  }
        |}""".stripMargin
    val parsed = read[PriceTable](example)
    assertEquals(parsed, table)

  // --- load ----

  test("load returns Missing when the file does not exist"):
    val tmp = os.temp.dir() / "nope.json"
    assertEquals(PriceTable.load(tmp), PriceTable.LoadOutcome.Missing)

  test("load returns Loaded for a valid file"):
    val tmp = os.temp(write(table), suffix = ".json")
    PriceTable.load(tmp) match
      case PriceTable.LoadOutcome.Loaded(t) => assertEquals(t, table)
      case other => fail(s"expected Loaded, got $other")

  test("load returns Malformed for invalid JSON"):
    val tmp = os.temp("{ this is not json", suffix = ".json")
    PriceTable.load(tmp) match
      case PriceTable.LoadOutcome.Malformed(_) => ()
      case other => fail(s"expected Malformed, got $other")

  test("load returns Malformed when schemaVersion is from the future"):
    val futureJson = write(table.copy(schemaVersion = 999))
    val tmp = os.temp(futureJson, suffix = ".json")
    PriceTable.load(tmp) match
      case PriceTable.LoadOutcome.Malformed(msg) =>
        assert(msg.contains("schemaVersion"), s"expected schemaVersion error, got: $msg")
      case other => fail(s"expected Malformed, got $other")

  test("load handles a partial table (one model present, others absent) — usdFor is per-model"):
    val partial = PriceTable(
      schemaVersion = PriceTable.CurrentSchemaVersion,
      models = Map("gpt-5-codex" -> gpt5codex)
    )
    val tmp = os.temp(write(partial), suffix = ".json")
    PriceTable.load(tmp) match
      case PriceTable.LoadOutcome.Loaded(t) =>
        assertEquals(t.usdFor("gpt-5-codex", CodexTokens(1_000_000L, 0, 0, 0)), Some(BigDecimal("1.25")))
        assertEquals(t.usdFor("gpt-other", CodexTokens(1_000_000L, 0, 0, 0)), None)
      case other => fail(s"expected Loaded, got $other")

  // --- shipped example ----

  test("prices.example.json (shipped resource) parses and covers the current Codex lineup"):
    // The shipped example must stay aligned with the OpenAI Codex models a
    // typical user will see (design §7.10(b); roadmap §2.1 + §3.3). Locking
    // the model set here so an out-of-date example fails CI rather than
    // silently degrading to `usd = 0` at runtime.
    val url = getClass.getResource("/prices.example.json")
    assert(url != null, "prices.example.json must ship as a resource under forge-agents")
    val raw = scala.io.Source.fromURL(url, "UTF-8").mkString
    val parsed = read[PriceTable](raw)
    assertEquals(parsed.schemaVersion, PriceTable.CurrentSchemaVersion)
    val expected = Set(
      "gpt-5-codex",
      "gpt-5.1-codex",
      "gpt-5.1-codex-max",
      "gpt-5.1-codex-mini",
      "gpt-5.2-codex",
      "gpt-5.3-codex",
      "codex-mini-latest"
    )
    val missing = expected -- parsed.models.keySet
    assert(missing.isEmpty, s"prices.example.json is missing Codex models: ${missing.mkString(", ")}")
