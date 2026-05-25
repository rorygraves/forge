# Forge design v0.7 — review commentary

> Review of `forge-design-0.7.md`. 0.7 successfully incorporates the 0.6 commentary: standalone spec, two first-class modes, split mechanism enums, bounded `SchemaFallback`, all-session question handling, settle-and-cost runaway bounds. The seven remaining findings are implementation-contract precision — state/schema edges that an implementer would otherwise have to invent. The companion `forge-design-0.8.md` applies these decisions as a standalone spec (per the policy established in §23 of 0.7).

**Reviewer:** Rory (consolidated review)  •  **Date:** 2026-05-25  •  **Target doc:** `forge-design-0.7.md`  •  **Precedes:** `forge-design-0.8.md`

---

## 1. `baseSha` should be nullable until branch creation

**Where:** forge-design-0.7.md:202 (initial manifest example), 214 (description says "captured at branch creation"), 746 (§11.4 step 1 persists it)

The manifest example at §5.1 shows `baseSha: "a1b2c3d4..."` for piece `p1` while the piece is still `status: "pending"` and has no PR. But §11.4 says baseSha is recorded by `createPieceBranch`, which happens later. The example is impossible.

**Decision for 0.8:**
- Make `baseSha` nullable: `"baseSha": null` for pending pieces.
- Update example to show `null` for an unstarted piece.
- Document explicitly: `baseSha` is `null` until `BranchManager.createPieceBranch` returns it; the FSM persists it before transitioning out of branch creation.
- Validator rule: a piece with `status != "pending"` must have a non-null `baseSha`.

---

## 2. ChangeCollector denial pre-PR has no valid resume hint

**Where:** forge-design-0.7.md:648 (denial → `RunAnotherFixup(p, prNumber)`)

In §11.4 (implementation phase), the order is: createPieceBranch → spawn driver → settle → **ChangeCollector** → commit → push → createPr. ChangeCollector runs **before** the PR exists. A denial at this point can't legally produce `RunAnotherFixup(p, prNumber)` because `prNumber` doesn't exist yet.

In §11.6 (fix-up), the order is: spawn driver (PR already exists) → settle → ChangeCollector → commit → push. Denial here *can* use `RunAnotherFixup(p, prNumber)`.

**Decision for 0.8:** Add a pre-PR resume hint and split ChangeCollector failure handling:

```scala
enum ResumeHint:
  // existing...
  case ResolveLocalImplementationChanges(p: PieceId, branch: BranchName)  // new
```

- Pre-PR denial (§11.4) → `NeedsHumanIntervention("change collector denied <path> in initial implementation", ResolveLocalImplementationChanges(p, branch))`. Human inspects local changes on the piece branch, either:
  - Cleans up and runs `forge resume --run-fixup <feature>` (re-spawns driver).
  - Manually edits to acceptable state and runs `forge resume --commit-human-fix <feature>` (Forge stages-via-policy, commits, pushes, opens PR).
- Post-PR denial (§11.6) → `RunAnotherFixup(p, prNumber)` as before.

`forge resume --commit-human-fix` already exists; this just extends its applicability to the pre-PR case. The `ResolveLocalImplementationChanges` hint surfaces in the TUI/CLI with the path and the two resume options.

---

## 3. `attempts` counter is ambiguous across CI vs review failures

**Where:** forge-design-0.7.md:763 (CI failure increments), 767 (review failure enters fix-up but increment not stated), 781 (exhaustion gated on `piece.attempts`)

The CI-failure path explicitly increments `piece.attempts`. The review-failure path enters fix-up but doesn't state the increment. The exhaustion check then gates on `piece.attempts > maxFixupRounds`. If reviews don't increment, a review-fix-up loop can run forever.

**Decision for 0.8:** Define `attempts` precisely as "fix-up rounds, regardless of source". Both CI-failure and review-failure paths increment the same counter. Update §11.5 to make the increment explicit on **both** paths:

- `PieceAwaitingCi` required-check failure: `piece.attempts += 1`. Check against `maxFixupRounds`.
- `PieceAwaitingReview` reviewer `request_changes`: `piece.attempts += 1`. Check against `maxFixupRounds`.
- `PieceAwaitingReview` human `CHANGES_REQUESTED` or comment: `piece.attempts += 1`. Check against `maxFixupRounds`.
- `PieceAwaitingMerge` late human feedback: `piece.attempts += 1`. Check against `maxFixupRounds`.

Rationale for one counter rather than split: the user-facing cap is "how many auto-fixup rounds before we surface this to the human". Splitting into `ciAttempts` and `reviewAttempts` lets a piece accrue `maxFixupRounds` of each and run twice as long without human review — that's not what the cap is for.

---

## 4. Missing branch-protection-required checks have no timeout transition

**Where:** forge-design-0.7.md:549 (required set = branch-protection ∪ overlay), 557 (missing overlay check timeout)

§8 step 2 catches a missing *overlay* check name but not a missing *branch-protection-required* check name. If a CI workflow file is deleted while branch protection still requires the check name (or the workflow is renamed without updating protection), Forge waits forever for a check that will never appear.

**Decision for 0.8:** Generalise the rule. After `checkDiscoveryTimeoutSec`, **any required check** (from the union of branch-protection-required and overlay) that is not present in the rollup triggers:

```
NeedsHumanIntervention(
  s"required check '${name}' never appeared (source: ${source})",
  ResumeAfterHumanPush(p, prNumber)
)
```

where `source` is `branch-protection` or `overlay`. The reviewer either fixes the CI config (so the check appears) or fixes the protection/overlay (so the check is no longer required). Either way, `forge resume --after-human-push` re-enters polling.

---

## 5. Design revision sessions have no settle timeout

**Where:** forge-design-0.7.md:519 (every driver session bounded by settle timeout), 722 (design revision after reviewer changes), 734 (design revision after PR feedback), 1067 (config defines only spec/implement/fixup timeouts)

The config exposes `settle.specTimeoutSec`, `implementTimeoutSec`, and `fixupTimeoutSec` but nothing for design revision. The doc claims every session has a bound but the bound isn't defined for this case.

**Decision for 0.8:** Add `settle.designRevisionTimeoutSec` (default **600s** — longer than spec's 300s because revision sessions carry more loaded context, shorter than implementation's 1800s because they edit docs not code):

```json
{
  "settle": {
    "specTimeoutSec": 300,
    "designRevisionTimeoutSec": 600,
    "implementTimeoutSec": 1800,
    "fixupTimeoutSec": 900
  }
}
```

Design revision sessions in §11.2 step 12 and §11.3 step 2 use this timeout. On expiry → `NeedsHumanIntervention("design revision settle timeout", ReopenDesign(Some(prNumber)))`.

---

## 6. `refineRetries` collides with the SchemaFallback hard cap

**Where:** forge-design-0.7.md:842 (refinery uses `config.<reviewer>.refineRetries`), 493 (SchemaFallback hard 2-attempt invariant)

If "schema-invalid" inside Refinery counts as a refineRetries retry, the user can effectively bypass the 2-attempt SchemaFallback cap (set `refineRetries: 5` and get 10 attempts). The config is contradictory.

**Decision for 0.8:** Rename and document the layering. Two distinct concepts at different levels:

- **`refineProcessRetries`** (renamed from `refineRetries`) — wraps around the *whole* refinery `exec` call. Retries only on **process-level failures**: network timeouts, sandbox launch errors, the CLI subprocess crashing, OS-level errors. Default: 2.
- **SchemaFallback 2-attempt cap** — *inside* the connector when `schemaMechanism == SchemaFallback`. Retries only on schema-validation failures. Hard invariant, not configurable.

Layering: `RetryOnProcessFailure(refineProcessRetries) { connector.refine(...) /* may use SchemaFallback internally */ }`.

A schema-validation failure that exhausts SchemaFallback returns an adapter error to the caller. The caller (Refinery) does *not* retry on adapter errors — those are content failures, not transport failures. Refinery's outcome on adapter error is the advisory-failure path (§14.2): log `harness.refinery_failed` and transition to `PieceMerged`.

Same rename applies to `claude.refineRetries` and `codex.refineRetries`.

---

## 7. `StreamingSession` resume capability is asserted but not exposed

**Where:** forge-design-0.7.md:418 (Connector trait), 889 (Slice 0: "Session resume on a streaming session"), 722 / 734 (design revision uses resume)

The `Connector` trait defines `runStreamingSpec(systemPrompt: Path): StreamingSession` but never specifies what `StreamingSession` is, and doesn't expose a resume method. Slice 0 requires resume validation; §11.2 and §11.3 use resume. The trait is missing the surface.

**Decision for 0.8:** Define `StreamingSession` explicitly and add resume to the connector:

```scala
trait StreamingSession:
  def sessionId: String                              // captured from CLI's init event
  def send(input: String): IO[Unit]                  // stdin → user message
  def events: Stream[IO, AgentEvent]                 // stdout → events
  def close(): IO[Unit]                              // graceful shutdown
  def kill(): IO[Unit]                               // SIGTERM/SIGKILL for runaway bounds

trait Connector:
  // Driver methods
  def runStreamingSpec(systemPrompt: Path): IO[StreamingSession]
  def resumeStreamingSpec(sessionId: String): IO[StreamingSession]    // new
  def runHeadlessImplementation(prompt: ImplementationPrompt): Stream[IO, AgentEvent]
  def runFixup(prompt: FixupPrompt): Stream[IO, AgentEvent]
  def questionMechanism: QuestionMechanism
  // ...
```

`runStreamingSpec` returns `IO[StreamingSession]` (was previously implicitly `StreamingSession`; the IO wrap acknowledges process spawn is effectful). `resumeStreamingSpec(sessionId)` is the explicit resume entry point.

Slice 0 validates `resumeStreamingSpec` works against a real prior session for both Claude and Codex. If a CLI lacks reliable resume, the design-scope decision per §1 Goal 6 applies.

§11.2 step 12 and §11.3 step 2 now read: "call `driver.resumeStreamingSpec(sessionId)`" rather than handwaving "resume driver design session".

---

## 8. Net assessment

0.7's architecture is unchanged in 0.8. The work is contract precision:

- One nullable field (`baseSha`).
- One new resume hint (`ResolveLocalImplementationChanges`).
- One semantic clarification (`attempts` = all fix-up rounds, not per-source).
- One generalised timeout transition (missing-required-check, not just missing-overlay).
- One new config knob (`designRevisionTimeoutSec`).
- One rename + layering clarification (`refineProcessRetries`).
- One added trait surface (`StreamingSession` + `resumeStreamingSpec`).

These are all things an implementer would otherwise hit on day 1–2 of Slice 1 and have to invent. Spec them now.

0.8 remains standalone per the §23 policy from 0.7. Length similar (~1200 lines).

---

## 9. What 0.8 does *not* change from 0.7

- All architectural decisions (FSM shape, Mode enum, bounded fallback protocols, manifest source of truth, audit/log split, ChangeCollector, CI policy, locking, budget enforcement).
- The §23 standalone-spec policy.
- v2 candidates list.
- Slice 0 → Slice 5 ordering and the 2×2 validation matrix.

The shape of the system is now stable across three consecutive revisions (0.5 → 0.6 → 0.7 → 0.8). The remaining work is implementation.
