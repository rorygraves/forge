package io.forge.app.bootstrap

/** Schema-shape drift guards (Slice 4 PR-A / A3). For each shipped reviewer JSON Schema, parse the file off the
  * forge-app classpath and assert that every field listed on the matching Scala ADT in `forge-agents/Reviews.scala`
  * appears as a property of the schema and (where applicable) in the schema's `required` array. Catches the case where
  * someone adds a Scala field but forgets to update the schema (or vice versa).
  *
  * These suites do not assert the schema is *valid* JSON Schema — that's downstream of the connectors' own
  * `--json-schema` / `--output-schema` rejection. They only assert *shape parity* between the ADT and the contract the
  * CLIs see.
  */

private object SchemaResources:
  def load(leaf: String): ujson.Obj =
    val cl = getClass.getClassLoader
    val path = s"reviewer/schemas/$leaf"
    val stream = cl.getResourceAsStream(path)
    require(stream ne null, s"missing reviewer schema on classpath: $path")
    try ujson.read(stream).obj
    finally stream.close()

  def requiredFields(schema: ujson.Obj): Set[String] =
    schema.value
      .get("required")
      .flatMap(_.arrOpt)
      .map(_.iterator.flatMap(_.strOpt).toSet)
      .getOrElse(Set.empty)

  def propertyFields(schema: ujson.Obj): Set[String] =
    schema.value
      .get("properties")
      .flatMap(_.objOpt)
      .map(_.keys.toSet)
      .getOrElse(Set.empty)

  /** Look inside `$defs.<name>` for a nested object schema. Returns the inner object's `required` / `properties` view.
    */
  def defObject(schema: ujson.Obj, name: String): ujson.Obj =
    val defs = schema.value
      .get("$defs")
      .flatMap(_.objOpt)
      .getOrElse(throw new AssertionError(s"missing top-level $$defs in schema"))
    defs.getOrElse(name, throw new AssertionError(s"missing $$defs.$name")) match
      case obj: ujson.Obj => obj
      case other =>
        throw new AssertionError(s"$$defs.$name expected object, got ${other.getClass.getSimpleName}")

  def verdictEnum(schema: ujson.Obj): Set[String] =
    val verdict = schema.value
      .get("properties")
      .flatMap(_.objOpt)
      .flatMap(_.get("verdict"))
      .flatMap(_.objOpt)
      .getOrElse(throw new AssertionError("missing properties.verdict"))
    verdict
      .get("enum")
      .flatMap(_.arrOpt)
      .map(_.iterator.flatMap(_.strOpt).toSet)
      .getOrElse(Set.empty)

  def stringEnum(schema: ujson.Obj, field: String): Set[String] =
    val node = schema.value
      .get("properties")
      .flatMap(_.objOpt)
      .flatMap(_.get(field))
      .flatMap(_.objOpt)
      .getOrElse(throw new AssertionError(s"missing properties.$field"))
    node
      .get("enum")
      .flatMap(_.arrOpt)
      .map(_.iterator.flatMap(_.strOpt).toSet)
      .getOrElse(Set.empty)

class DesignReviewSchemaShapeSuite extends munit.FunSuite:

  private val schema: ujson.Obj = SchemaResources.load("design-review.json")

  test("design-review.json declares every DesignReview field as a property"):
    val expected = Set("verdict", "blockers", "questions", "summary")
    assertEquals(SchemaResources.propertyFields(schema), expected)

  test("design-review.json marks every DesignReview field as required"):
    val expected = Set("verdict", "blockers", "questions", "summary")
    assertEquals(SchemaResources.requiredFields(schema), expected)

  test("verdict enum matches ReviewVerdict's wire encoding"):
    assertEquals(SchemaResources.verdictEnum(schema), Set("approve", "request_changes"))

  test("blocker $def declares every ReviewBlocker field, all in required (OpenAI strict mode)"):
    val blocker = SchemaResources.defObject(schema, "blocker")
    val expected = Set("summary", "path", "line", "anchorText")
    assertEquals(SchemaResources.propertyFields(blocker), expected)
    // Codex --output-schema (OpenAI strict mode) requires every property in `required`; optionals are nullable
    // (path/line/anchorText are `["…","null"]`). See design-rationale C17.
    assertEquals(SchemaResources.requiredFields(blocker), expected)

  test("question $def declares every Question field, all in required (OpenAI strict mode)"):
    val question = SchemaResources.defObject(schema, "question")
    val expected = Set("text", "options", "allowFreeText", "severity")
    assertEquals(SchemaResources.propertyFields(question), expected)
    assertEquals(SchemaResources.requiredFields(question), expected)
    assertEquals(
      SchemaResources.stringEnum(question, "severity"),
      Set("blocking", "clarifying", "optional")
    )

class PrReviewSchemaShapeSuite extends munit.FunSuite:

  private val schema: ujson.Obj = SchemaResources.load("code-review.json")

  test("code-review.json declares every PrReview field as a property"):
    val expected = Set("verdict", "blockers", "summary")
    assertEquals(SchemaResources.propertyFields(schema), expected)

  test("code-review.json marks every PrReview field as required"):
    val expected = Set("verdict", "blockers", "summary")
    assertEquals(SchemaResources.requiredFields(schema), expected)

  test("verdict enum matches ReviewVerdict's wire encoding"):
    assertEquals(SchemaResources.verdictEnum(schema), Set("approve", "request_changes"))

  test("blocker $def declares every ReviewBlocker field, all in required (OpenAI strict mode)"):
    val blocker = SchemaResources.defObject(schema, "blocker")
    val expected = Set("summary", "path", "line", "anchorText")
    assertEquals(SchemaResources.propertyFields(blocker), expected)
    assertEquals(SchemaResources.requiredFields(blocker), expected)

class RefineSchemaShapeSuite extends munit.FunSuite:

  private val schema: ujson.Obj = SchemaResources.load("refine.json")

  test("refine.json declares every RefineResult field as a property"):
    // `patch` mirrors `RefineResult.patchJson` after wire-name translation. Detailed shape is enforced by
    // RefineSchemaValidationSuite.
    val expected = Set("outcome", "reason", "patch")
    assertEquals(SchemaResources.propertyFields(schema), expected)

  test("refine.json marks every RefineResult field as required (OpenAI strict mode)"):
    // OpenAI strict mode (Codex --output-schema) requires every property in `required`. `patch` is therefore
    // always-required-but-nullable; the "patch present iff outcome == update_plan" invariant moved to
    // ReviewDecoders.refineResult (design-rationale C17). The schema can no longer express it (if/then/allOf
    // are forbidden under strict mode).
    assertEquals(SchemaResources.requiredFields(schema), Set("outcome", "reason", "patch"))

  test("outcome enum matches RefineOutcome's wire encoding"):
    assertEquals(
      SchemaResources.stringEnum(schema, "outcome"),
      Set("no_change", "update_plan", "reopen_design")
    )

  test("patch is nullable (anyOf manifestPatch | null) and the schema carries no forbidden strict-mode keywords"):
    // Replaces the former allOf/if-then conditional-patch test: the conditional is decoder-enforced now (C17).
    // patch must be an anyOf of {manifestPatch ref, null} so Codex strict mode accepts it while update_plan can
    // still carry a real patch.
    val patch = schema.value
      .get("properties")
      .flatMap(_.objOpt)
      .flatMap(_.get("patch"))
      .flatMap(_.objOpt)
      .getOrElse(throw new AssertionError("missing properties.patch"))
    val anyOf = patch.value
      .get("anyOf")
      .flatMap(_.arrOpt)
      .getOrElse(throw new AssertionError("patch must be an anyOf (manifestPatch | null)"))
    assert(
      anyOf.iterator.flatMap(_.objOpt).exists(_.get("type").flatMap(_.strOpt).contains("null")),
      "patch anyOf must include a null branch"
    )
    // Strict mode forbids these structural/validation keywords anywhere in the schema; guard against reintroduction.
    val raw = ujson.write(schema)
    for kw <- Seq("\"oneOf\"", "\"allOf\"", "\"if\"", "\"then\"", "\"not\"", "\"minimum\"", "\"maximum\"") do
      assert(!raw.contains(kw), s"refine.json must not use $kw (forbidden by Codex OpenAI strict mode; C17)")

  test("manifestPatch $def declares ManifestPatch's wire fields"):
    val patchDef = SchemaResources.defObject(schema, "manifestPatch")
    assertEquals(SchemaResources.propertyFields(patchDef), Set("reason", "ops"))
    assertEquals(SchemaResources.requiredFields(patchDef), Set("reason", "ops"))

  test("manifestPatchOp $def uses anyOf with one branch per ManifestPatchOp variant"):
    // anyOf, not oneOf — Codex strict mode forbids oneOf. The $type const discriminator keeps the branches
    // mutually exclusive in practice, and ReviewDecoders dispatches on $type.
    val opDef = SchemaResources.defObject(schema, "manifestPatchOp")
    val anyOf = opDef.value
      .get("anyOf")
      .flatMap(_.arrOpt)
      .getOrElse(throw new AssertionError("manifestPatchOp must use an anyOf branch list"))
    assertEquals(anyOf.size, 4, s"expected one branch per ManifestPatchOp variant; got ${anyOf.size}")

  test("each ManifestPatchOp variant $def pins its $type discriminator"):
    val expected = Map(
      "op_addPiece" -> "AddPiece",
      "op_removePiece" -> "RemovePiece",
      "op_editPiece" -> "EditPiece",
      "op_reorderPieces" -> "ReorderPieces"
    )
    expected.foreach { case (defName, variantName) =>
      val variant = SchemaResources.defObject(schema, defName)
      val typeConst = variant.value
        .get("properties")
        .flatMap(_.objOpt)
        .flatMap(_.get("$type"))
        .flatMap(_.objOpt)
        .flatMap(_.get("const"))
        .flatMap(_.strOpt)
        .getOrElse(throw new AssertionError(s"$defName: missing properties.$$type.const"))
      assertEquals(typeConst, variantName)
    }
