package io.forge.app.bootstrap

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.networknt.schema.{JsonSchemaFactory, SpecVersion, ValidationMessage}

import io.forge.core.*
import io.forge.core.manifest.*

import scala.jdk.CollectionConverters.*

/** Validator-backed positive/negative tests for `refine.json` (Slice 4 PR-A review-round-1 P2). Builds real
  * `RefineResult`-shaped JSON via uPickle — including a full `ManifestPatch` constructed from `ManifestPatchOp`
  * variants — and runs it through networknt's JSON Schema validator. Catches drift between the schema and the wire
  * shape uPickle actually emits.
  *
  * Why a separate suite from [[RefineSchemaShapeSuite]]: the shape suite checks the *schema document's* structure
  * (required fields present, oneOf branches present); this suite checks that real serialised payloads validate / reject
  * as the §14.3 contract demands.
  */
class RefineSchemaValidationSuite extends munit.FunSuite:

  private val schemaJson: String = ujson.write(SchemaResources.load("refine.json"))

  private val schema = JsonSchemaFactory
    .getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(schemaJson)

  private val mapper: ObjectMapper = ObjectMapper()

  private def validate(json: String): Vector[String] =
    val node: JsonNode = mapper.readTree(json)
    val msgs: java.util.Set[ValidationMessage] = schema.validate(node)
    msgs.asScala.iterator.map(_.getMessage).toVector

  // --- fixtures --------------------------------------------------------------

  private def newPiece(id: String): Piece =
    Piece(
      id = PieceId(id),
      order = 1,
      title = s"piece $id",
      summary = s"summary for $id",
      specPath = s".forge/specs/feat/pieces/$id.md",
      acceptanceHash = "sha256:0".padTo(71, '0'),
      status = PieceStatus.Pending,
      baseSha = None,
      prNumber = None,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

  private def manifestPatchJson(ops: ManifestPatchOp*): String =
    upickle.default.write(ManifestPatch(reason = "refinery: rework remaining pieces", ops = ops.toVector))

  private def refineJson(outcome: String, reason: String, patchJson: Option[String] = None): String =
    val patchField = patchJson.map(p => s""", "patch": $p""").getOrElse("")
    s"""{ "outcome": "$outcome", "reason": "$reason"$patchField }"""

  // --- happy paths -----------------------------------------------------------

  test("no_change without patch validates clean"):
    val errs = validate(refineJson("no_change", "everything still tracks"))
    assert(errs.isEmpty, s"unexpected validation errors: $errs")

  test("reopen_design without patch validates clean"):
    val errs = validate(refineJson("reopen_design", "design assumption violated"))
    assert(errs.isEmpty, s"unexpected validation errors: $errs")

  test("update_plan with a real ManifestPatch.AddPiece validates clean"):
    val patch = manifestPatchJson(ManifestPatchOp.AddPiece(after = Some(PieceId("p2")), piece = newPiece("p3")))
    val errs = validate(refineJson("update_plan", "split into auth + storage", Some(patch)))
    assert(errs.isEmpty, s"unexpected validation errors against real uPickle patch: $errs\npatch=$patch")

  test("update_plan with a real ManifestPatch.RemovePiece validates clean"):
    val patch = manifestPatchJson(ManifestPatchOp.RemovePiece(PieceId("p3")))
    val errs = validate(refineJson("update_plan", "drop dead piece", Some(patch)))
    assert(errs.isEmpty, s"unexpected validation errors: $errs\npatch=$patch")

  test("update_plan with a real ManifestPatch.EditPiece validates clean"):
    val patch = manifestPatchJson(
      ManifestPatchOp.EditPiece(
        PieceId("p2"),
        title = Some("Auth path"),
        summary = None,
        specPath = None,
        acceptanceHash = None
      )
    )
    val errs = validate(refineJson("update_plan", "tighten title", Some(patch)))
    assert(errs.isEmpty, s"unexpected validation errors: $errs\npatch=$patch")

  test("update_plan with a real ManifestPatch.ReorderPieces validates clean"):
    val patch = manifestPatchJson(
      ManifestPatchOp.ReorderPieces(Vector(PieceId("p1"), PieceId("p3"), PieceId("p2")))
    )
    val errs = validate(refineJson("update_plan", "reorder pending pieces", Some(patch)))
    assert(errs.isEmpty, s"unexpected validation errors: $errs\npatch=$patch")

  test("update_plan with a multi-op ManifestPatch validates clean"):
    val patch = manifestPatchJson(
      ManifestPatchOp.EditPiece(PieceId("p2"), title = Some("Auth"), None, None, None),
      ManifestPatchOp.AddPiece(after = Some(PieceId("p2")), piece = newPiece("p4"))
    )
    val errs = validate(refineJson("update_plan", "split p2; insert p4", Some(patch)))
    assert(errs.isEmpty, s"unexpected validation errors: $errs\npatch=$patch")

  // --- negative paths (§14.3 invariants) -------------------------------------

  test("update_plan without patch is rejected (§14.3 invariant: patch required)"):
    val errs = validate(refineJson("update_plan", "missing patch"))
    assert(errs.nonEmpty, "expected validation to reject update_plan with no patch")

  test("no_change with a stray patch is rejected (schema must forbid it, not silently drop)"):
    val patch = manifestPatchJson(ManifestPatchOp.RemovePiece(PieceId("p3")))
    val errs = validate(refineJson("no_change", "fine, but I sent a patch anyway", Some(patch)))
    assert(errs.nonEmpty, s"expected validation to reject stray patch on no_change; errors=$errs")

  test("reopen_design with a stray patch is rejected"):
    val patch = manifestPatchJson(ManifestPatchOp.RemovePiece(PieceId("p3")))
    val errs = validate(refineJson("reopen_design", "open the design again", Some(patch)))
    assert(errs.nonEmpty, s"expected validation to reject stray patch on reopen_design; errors=$errs")

  test("update_plan with a free-form (non-ManifestPatch-shaped) patch is rejected"):
    // The unconstrained pre-tightening behaviour: a bare {} would pass. After tightening, patch MUST have
    // reason + ops, and any extra keys are forbidden.
    val errs = validate(refineJson("update_plan", "shape-violating patch", Some("""{}""")))
    assert(errs.nonEmpty, s"expected validation to reject empty-object patch; errors=$errs")

  test("update_plan with an op missing the $type discriminator is rejected"):
    val patch = """{"reason": "no discriminator", "ops": [{"id": "p3"}]}"""
    val errs = validate(refineJson("update_plan", "missing discriminator", Some(patch)))
    assert(errs.nonEmpty, s"expected validation to reject op with no $$type tag; errors=$errs")

  test("update_plan with an unknown op type is rejected"):
    val patch = """{"reason": "unknown variant", "ops": [{"$type": "RewriteHistory", "wat": true}]}"""
    val errs = validate(refineJson("update_plan", "bogus op", Some(patch)))
    assert(errs.nonEmpty, s"expected validation to reject unknown op variant; errors=$errs")

  test("update_plan with an unknown outcome value is rejected"):
    val errs = validate(refineJson("disapprove", "wrong outcome"))
    assert(errs.nonEmpty, s"expected validation to reject unknown outcome; errors=$errs")

  test("schema rejects additional top-level fields"):
    val payload = """{ "outcome": "no_change", "reason": "fine", "extra": true }"""
    val errs = validate(payload)
    assert(errs.nonEmpty, s"expected validation to reject extra top-level field; errors=$errs")
