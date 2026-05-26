package io.forge.core.property

import io.forge.core.fsm.*
import io.forge.core.gen.Generators

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** PR-F F7 — §17 slice-2 invariant 8: `requireSessionId` returns `Left` rather than throwing on `None`.
  *
  * Property: for every (reason, hint) the helper sees with `sessionId = None`, the result is
  * `Left(FsmTransition(NeedsHumanIntervention(reason, hint)))`. Never throws. And for `Some(id)`, returns `Right(id)`.
  *
  * The §11.0 step 5 design says "no other resume calls in v1; this rule covers all current cases" — i.e. the helper is
  * the single funnel. We exercise both polarities and a variety of hints.
  */
class F7RequireSessionIdSuite extends ScalaCheckSuite:

  private val genHint: Gen[ResumeHint] = Gen.oneOf(
    Gen.const(ResumeHint.AbortOrAbandon),
    Gen.const(ResumeHint.ReopenDesign(None)),
    Generators.genPrNumber.map(pr => ResumeHint.ReopenDesign(Some(pr))),
    for p <- Generators.genPieceId; pr <- Generators.genPrNumber yield ResumeHint.RunAnotherFixup(p, pr),
    for p <- Generators.genPieceId; pr <- Generators.genPrNumber yield ResumeHint.ResumeAfterHumanPush(p, pr),
    for p <- Generators.genPieceId; pr <- Generators.genPrNumber yield ResumeHint.CommitAndPushHumanFix(p, pr)
  )

  property("F7 — requireSessionId(None, _, _) returns Left(FsmTransition(NHI(...)))") {
    forAll(Gen.alphaNumStr, genHint) { (reason: String, hint: ResumeHint) =>
      Fsm.requireSessionId(None, reason, hint) match
        case Left(FsmTransition(FsmState.NeedsHumanIntervention(r, h))) =>
          ((r == reason) :| s"reason mismatch: $r vs $reason") &&
          ((h == hint) :| s"hint mismatch: $h vs $hint")
        case other => falsified :| s"expected Left(NHI), got $other"
    }
  }

  property("F7 — requireSessionId(Some(id), _, _) returns Right(id)") {
    forAll(Gen.alphaNumStr.suchThat(_.nonEmpty), Gen.alphaNumStr, genHint) {
      (sid: String, reason: String, hint: ResumeHint) =>
        Fsm.requireSessionId(Some(sid), reason, hint) match
          case Right(id) => (id == sid) :| s"id mismatch: $id vs $sid"
          case other => falsified :| s"expected Right(sid), got $other"
    }
  }

  property("F7 — requireSessionId never throws on any input") {
    forAll(Gen.option(Gen.alphaNumStr), Gen.alphaNumStr, genHint) {
      (sidOpt: Option[String], reason: String, hint: ResumeHint) =>
        try
          Fsm.requireSessionId(sidOpt, reason, hint)
          proved
        catch case t: Throwable => falsified :| s"threw $t"
    }
  }
