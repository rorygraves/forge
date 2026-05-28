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

  test("blocker $def declares every ReviewBlocker field"):
    val blocker = SchemaResources.defObject(schema, "blocker")
    val expected = Set("summary", "path", "line", "anchorText")
    assertEquals(SchemaResources.propertyFields(blocker), expected)
    assertEquals(SchemaResources.requiredFields(blocker), Set("summary"))

  test("question $def declares every Question field"):
    val question = SchemaResources.defObject(schema, "question")
    val expected = Set("text", "options", "allowFreeText", "severity")
    assertEquals(SchemaResources.propertyFields(question), expected)
    assertEquals(SchemaResources.requiredFields(question), Set("text", "severity"))
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

  test("blocker $def declares every ReviewBlocker field"):
    val blocker = SchemaResources.defObject(schema, "blocker")
    val expected = Set("summary", "path", "line", "anchorText")
    assertEquals(SchemaResources.propertyFields(blocker), expected)
    assertEquals(SchemaResources.requiredFields(blocker), Set("summary"))

class RefineSchemaShapeSuite extends munit.FunSuite:

  private val schema: ujson.Obj = SchemaResources.load("refine.json")

  test("refine.json declares every RefineResult field as a property"):
    // `patch` mirrors `RefineResult.patchJson` after wire-name translation; declared here even though only
    // conditionally required when outcome == update_plan.
    val expected = Set("outcome", "reason", "patch")
    assertEquals(SchemaResources.propertyFields(schema), expected)

  test("refine.json marks the always-required RefineResult fields as required"):
    val required = SchemaResources.requiredFields(schema)
    assert(required.contains("outcome"), s"expected 'outcome' in $required")
    assert(required.contains("reason"), s"expected 'reason' in $required")
    // `patch` is conditionally required via allOf/if-then; not in the top-level required set.
    assert(!required.contains("patch"), s"'patch' should be conditionally required, not top-level: $required")

  test("outcome enum matches RefineOutcome's wire encoding"):
    assertEquals(
      SchemaResources.stringEnum(schema, "outcome"),
      Set("no_change", "update_plan", "reopen_design")
    )

  test("schema enforces patch presence when outcome == update_plan"):
    // The allOf/if-then block is the §14.3 invariant: update_plan MUST carry a patch.
    val allOf = schema.value
      .get("allOf")
      .flatMap(_.arrOpt)
      .getOrElse(throw new AssertionError("expected top-level allOf array for conditional patch requirement"))
    val hasConditional = allOf.exists { entry =>
      val obj: collection.mutable.Map[String, ujson.Value] =
        entry.objOpt.getOrElse(collection.mutable.Map.empty)
      val ifClause = obj.get("if").flatMap(_.objOpt)
      val thenClause = obj.get("then").flatMap(_.objOpt)
      val condTargets = ifClause
        .flatMap(_.get("properties"))
        .flatMap(_.objOpt)
        .flatMap(_.get("outcome"))
        .flatMap(_.objOpt)
        .flatMap(_.get("const"))
        .flatMap(_.strOpt)
      val thenRequired = thenClause
        .flatMap(_.get("required"))
        .flatMap(_.arrOpt)
        .map(_.iterator.flatMap(_.strOpt).toSet)
        .getOrElse(Set.empty)
      condTargets.contains("update_plan") && thenRequired.contains("patch")
    }
    assert(hasConditional, "refine.json must enforce required patch when outcome == update_plan")
