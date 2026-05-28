package io.forge.app.bootstrap

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.networknt.schema.{JsonSchemaFactory, SpecVersion, ValidationMessage}

import io.forge.core.*
import io.forge.core.manifest.*

import scala.jdk.CollectionConverters.*

/** Validator-backed positive/negative tests for `refine.json` (Slice 4 PR-A review-round-1 P2; updated Task 1.4.7
  * review round 1 for Codex OpenAI-strict-mode compatibility — design-rationale C17). Builds real `RefineResult`-shaped
  * JSON via uPickle — including a full `ManifestPatch` constructed from `ManifestPatchOp` variants — and runs it
  * through networknt's JSON Schema validator. Catches drift between the schema and the wire shape uPickle actually
  * emits.
  *
  * Why a separate suite from [[RefineSchemaShapeSuite]]: the shape suite checks the *schema document's* structure
  * (required fields present, anyOf branches present); this suite checks that real serialised payloads validate / reject
  * as the schema demands.
  *
  * **What the schema enforces vs what the decoder enforces (C17).** Under OpenAI strict mode the schema cannot use
  * `if/then/allOf`, so `patch` is always-required-but-nullable and the schema no longer expresses the §14.3 "patch
  * present iff outcome == update_plan" invariant. That invariant is enforced by `ReviewDecoders.refineResult` (covered
  * by `ReviewDecodersSuite`). This suite therefore checks only the structural contract the schema still owns: `patch`
  * key present, patch shape valid when non-null, `additionalProperties:false`, and the `outcome` enum.
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

  // `patch` is always required under strict mode; emit `null` when no patch is supplied.
  private def refineJson(outcome: String, reason: String, patchJson: Option[String] = None): String =
    val patch = patchJson.getOrElse("null")
    s"""{ "outcome": "$outcome", "reason": "$reason", "patch": $patch }"""

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

  test("update_plan with patch:null validates at the schema level (non-null is decoder-enforced — C17)"):
    // Strict mode can't express "patch required iff update_plan", so patch:null is schema-valid for every outcome;
    // ReviewDecoders.refineResult rejects update_plan + null (ReviewDecodersSuite covers it).
    val errs = validate(refineJson("update_plan", "model forgot the patch"))
    assert(errs.isEmpty, s"schema should accept update_plan+null; the non-null check is the decoder's: $errs")

  test("no_change with a real patch validates at the schema level (decoder drops it — C17)"):
    val patch = manifestPatchJson(ManifestPatchOp.RemovePiece(PieceId("p3")))
    val errs = validate(refineJson("no_change", "stray patch is schema-legal now; decoder drops it", Some(patch)))
    assert(errs.isEmpty, s"schema should accept a stray patch; the drop is the decoder's: $errs")

  // --- negative paths (structural contract the schema still owns) -------------

  test("payload missing the required patch key is rejected (strict mode: every property required)"):
    val errs = validate("""{ "outcome": "no_change", "reason": "no patch key at all" }""")
    assert(errs.nonEmpty, "expected validation to reject a payload with no 'patch' key")

  test("update_plan with a free-form (non-ManifestPatch-shaped) patch is rejected"):
    // patch is anyOf {manifestPatch, null}; a bare {} is neither (manifestPatch needs reason + ops).
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
    val payload = """{ "outcome": "no_change", "reason": "fine", "patch": null, "extra": true }"""
    val errs = validate(payload)
    assert(errs.nonEmpty, s"expected validation to reject extra top-level field; errors=$errs")
