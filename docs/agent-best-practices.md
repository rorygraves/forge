# Agent best practices

> Cross-cutting discipline rules distilled from many cycles of
> LLM-driven design + implementation work. Written so it can be
> shipped to driver / reviewer agents as part of Forge's
> system-prompt scaffolding, and read by humans working on Forge.
> Project-agnostic on purpose — examples are illustrative, not
> tied to any one repo.

These rules exist because each one names a failure mode that
recurred across multiple review cycles. Following any one of them
catches a class of bugs that would otherwise surface as a second
or third round of review comments. Following all of them
compresses a typical 3–4 round review cycle into 1–2.

The rules are ordered roughly by leverage — the earliest items
give the largest cycle-time reduction.

---

## 1. Run code early — prefer a thin runnable slice over a thick design pass

When opening a new section of work, the first sub-PR should put
*executing* code in front of the riskiest contract, not refine
the design document for another iteration. The riskiest contract
is the one piece that, if wrong, invalidates the rest of the
section: a state-machine signature, a wire format an external
tool must satisfy, a JSON-schema-as-contract, a cursor invariant.

What "thin runnable slice" can look like:

- A 50-line spike that exercises the contract end-to-end against
  a stub, capturing a fixture along the way.
- A property-test harness against an existing module that pins
  the invariant.
- A real-CLI / real-API capture plus the decoder that consumes
  it, with one test that round-trips a known sample.
- A single integration test against the external tool, even if
  the production wrapper isn't written yet.

Why this matters: a paragraph of design prose can survive three
review rounds. A green test cannot. The driver and reviewer can
argue about whether `foo: Option[Int]` is "right"; they cannot
argue with stdout from the live binary.

This **does not** replace the design document. The design still
leads. But the design absorbs feedback from running code, rather
than absorbing it from review rounds.

Anti-pattern: a doc-only PR-A that extends the design across
several new concepts (helper types, error channels, lifecycle
states) without grounding any of them in executing code. Review
rounds on this shape compound, because each round's fix has
implications for the still-unbuilt adjacent parts of the design.

## 2. Coherence over local patches

When a reviewer flags a contract problem — a type signature, a
trait method, an error channel, a state name — *do not* edit
only the spot they pointed at and call it done. Re-walk the
call chain end to end:

- Every **producer** of the changed type (anything that
  constructs it or calls the method).
- Every **consumer** (anything that pattern-matches on it or
  invokes the method).
- Every **cross-reference** in the same doc, in adjacent design
  docs, and in dependent sub-PRs.
- Every **test** that asserts against the old contract.
- The **exit criterion** and **status log** of the active plan.

After applying the fix, re-read the document end-to-end one more
time looking specifically for stale wording from the *previous*
contract — old names, old signatures, old phrasing of
dependencies. These survive local edits and accumulate as the
doc evolves.

Why: reviewer comments are signals about the surrounding
contract, not isolated patch requests. Treating them as patch
requests guarantees the next round will flag the next
implication. Multi-round design cycles are almost always this
pattern.

## 3. Two rounds on the same contract → reconciliation note, not another patch

If two consecutive review rounds flag the same underlying
contract in different cells, stop patching. Write a
one-paragraph **contract reconciliation note** enumerating every
affected surface — producers, consumers, cross-references,
dependent tests, exit criterion, later sub-PR handoffs — then
patch all of them in one diff.

The note is cheap. The unpatched implications are not: they
become round 3, round 4, round 5.

A useful template:

> **Reconciliation note (round N):** Round N flagged X. The
> change implies edits at A, B, C, D, E (enumerated). Producers:
> P1, P2. Consumers: C1, C2. Dependent tests: T1, T2. Status
> log + exit criterion: §X.Y, §X.Z. Patching all together below.

## 4. Capture real external shapes before writing decoders, schemas, or flag tables

Before writing code that parses output from an external tool, or
that asserts which CLI flags exist, **capture a real sample**:

- `gh ... --json ... > fixtures/sample.json`
- `claude --help`, `codex --help`
- The live pricing page snapshot
- A real API response, captured with the actual version pinned
  by the project

Pin the capture as a test fixture or a design-rationale snippet.
**Never** derive field or flag names from prior versions, from
the spec alone, or from another tool. They drift between
releases and the drift is silent.

This rule is distinct from "write integration tests for
subprocess paths" — that one is about *lifecycle*. This one is
about *wire shape*. Both apply.

Common failure shapes this catches:

- A decoder that asserts on a field the current version of the
  tool no longer emits — every real input would `MissingField`.
- A CLI invocation that omits a required flag because the
  driver wrote against an older spec.
- A pricing or flag table invented from memory, landing in a
  shipped fixture.

## 5. Mirror existing test idioms — especially for lifecycle

Before writing a new test for a module that has analogous
existing tests, grep for the closest sibling and read its
lifecycle carefully. Lifecycle order is usually load-bearing.

The two patterns that bite hardest:

- **Close-then-drain** is the safe order for streaming /
  multi-process facades whose events channel only closes after
  the underlying subprocess exits. `collect-then-close`
  deadlocks. The exception is one-shot headless mode where the
  process exits on its own.
- **Async finalizers must publish observable side effects
  before resolving the outcome other fibers wait on.** Wrap
  fallible side effects in `.attempt` so a failure cannot
  orphan the caller's wait.

Anti-pattern: inventing a lifecycle when the codebase has
settled one. The pattern was there for a reason, usually a bug
that bit a prior session.

## 6. Invariants live at the helper, not the call site

When fixing an invariant violation pointed at one code path,
ask: where does the data flow funnel through? Write the
assertion at the **helper / constructor / transition function**
that all paths funnel through, not inline at the cited line.

Why: sibling paths feeding the same data will leak the same bug
otherwise. The reviewer is pointing at the symptom, not the
home of the invariant.

Useful question: "If I add this check here, and tomorrow
someone adds a new code path that hits the same data, will they
remember to add the check?" If no, the check is in the wrong
place.

## 7. Ask before scope-expanding — and ask mid-round, not just at scope-set

When mid-task work surfaces a fix outside the originally-scoped
PR, or when a review round surfaces a finding whose fix could
be either "patch the cited cell" or "rewrite the surrounding
contract," ask. Two or three concrete options, with a
recommendation.

Cost of asking: one round-trip.
Cost of silent expansion: a sprawling PR with mixed concerns
and no audit trail of the scope decision.
Cost of patching when you should have rewritten: one or two
extra review rounds.

This applies even in autonomous / auto modes. Auto-mode is
licence for taking the *original* task to completion without
clarification, not licence for unscoped expansion.

## 8. Sibling diff before declaring done

Before declaring a sub-PR complete, do an explicit consistency
sweep: for each new or modified file, find its closest
analogous file in the same change (or in the existing codebase)
and diff lifecycle, error handling, invariant checks, and
resource cleanup. Look for "handled HERE but not THERE" gaps.

Most "obvious in hindsight" review comments come from this
gap — the knowledge wasn't missing, the consistency check was.
The reviewer is effectively running this diff anyway; running
it before commit costs much less.

## 9. Don't tick checklist items during a review round

Do not flip `[ ]` → `[x]` in the same commit as a change that
is still under review. Premature ticks mask outstanding issues
and force a "round N+1 to un-tick" later.

Tick after the round closes. Tick the section-level item (e.g.
roadmap bullet) only after the full section's code review
passes.

## 10. Spec deviations are flags, not resolutions

Code comments that say "we deviate from spec §X because…" are
not done work. They are pending decisions. File the gap as a
numbered entry in the project's deferred-decisions log, rewrite
the code comment to point at that entry, and add a
carry-forward item to the active plan so the section close
can't bury it.

A code comment is read by whoever happens to land in that file
next. A deferred-decisions log is read on purpose.

## 11. Commit completed work before review

When a sub-PR's landing checklist passes (compile, tests,
formatting), commit it immediately — before reporting completion
or moving on. Don't leave the work untracked waiting for a user
prompt or for review feedback. The default risk is that the
"completed" work doesn't appear on the PR / in CI at all.

## 12. Push back when the evidence supports it

When a review finding contradicts a finding from the previous
round, or when the finding misreads the contract, *justify*
before patching. Two options + reasoning in one short
paragraph: "I think the previous round's fix is correct
because… If you still want me to change it, here's the
alternative." Patching reflexively without checking can re-open
a settled question.

This is asymmetric with rule 2: rule 2 is about taking the
finding seriously enough to walk the surrounding contract.
Rule 12 is about not assuming every finding is correct just
because it's a finding. Both apply.

---

## Applying these rules in autonomous mode

When working without an interactive human-in-the-loop:

- Rules 1, 2, 4, 5, 6, 8, 10, 11 apply unconditionally; they
  are pre-commit discipline.
- Rule 3 (two-rounds reconciliation) and Rule 7 (ask) require
  the loop to track review-round count and to surface scope
  questions to whoever is closing the loop. If that's another
  agent, frame the question as a structured option set so the
  receiving agent can answer mechanically.
- Rule 9 (don't tick during review) is satisfied by separating
  "implementation commit" from "tick commit" — the latter
  lands only after the review round closes.
- Rule 12 (push back) requires confidence calibration — only
  push back when the evidence is concrete (a captured fixture,
  a prior round's reconciliation note, a spec citation), not
  on general impressions.

## Quick reference

When opening a section: rule 1 (run code early), rule 4
(capture real shapes), rule 5 (mirror idioms).

During implementation: rule 6 (invariants at the helper),
rule 8 (sibling diff), rule 11 (commit when done).

During a review round: rule 2 (coherence), rule 7 (ask if
ambiguous), rule 12 (push back when supported).

After two rounds on the same contract: rule 3 (reconciliation
note).

Before declaring done: rule 8 (sibling diff) again, rule 9
(don't tick during review).

When finishing the section: rule 10 (file deferrals durably).
