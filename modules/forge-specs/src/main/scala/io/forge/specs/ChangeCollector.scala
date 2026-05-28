package io.forge.specs

import cats.effect.IO
import io.forge.core.{Question, QuestionSeverity}
import io.forge.core.paths.ForgePaths

import java.nio.file.{FileSystems, PathMatcher, Paths}

/** Task 1.4.5 E1 — classifies a working-tree change set into the §10.1 trichotomy before Forge stages it.
  *
  * The classification is **phase-agnostic**: `classify` answers "may these files be staged?" and nothing about which
  * lifecycle phase asked. The orchestrator (Slice 1.4b Task 1.4.10) maps the result into the phase-appropriate
  * `ResumeHint` per §10.1's phase-aware denial table (`ResolveLocalImplementationChanges` pre-PR vs `RunAnotherFixup`
  * post-PR — E4).
  *
  * `classify` is pure over its inputs (no filesystem or git access — the git knowledge rule 4 needs arrives on
  * [[FileChange.gitIgnored]]); the `IO` is for composition with the orchestrator and to capture a malformed-glob
  * `PatternSyntaxException` from an operator-customised `denyPatterns` / `allowPatterns` entry.
  */
trait ChangeCollector:
  def classify(
      repoRoot: os.Path,
      changes: Vector[FileChange],
      config: StagingConfig
  ): IO[Classification]

/** Task 1.4.5 E1 — the §10.1 trichotomy, aggregated over the whole change set.
  *
  * Precedence is `Deny` > `Ask` > `Allow`: a single denied path blocks the whole stage (§10.1's pre-PR denial halts
  * with `NeedsHumanIntervention`, naming the path), and any unresolved `Ask` holds staging until the human answers.
  *
  *   - [[Allow]] — every change may be staged.
  *   - [[Deny]] — at least one change is forbidden; carries every `(change, reason)` pair so the operator message can
  *     name them. `reason` is the matched deny pattern (rule 1) or a rule descriptor (rules 2–4).
  *   - [[Ask]] — strict mode (rule 5) surfaced at least one borderline path; `questions` is one per asked path (default
  *     option `Deny`, applied by the orchestrator on no-answer), `included` is the set that passed cleanly.
  */
enum Classification:
  case Allow(included: Vector[FileChange])
  case Deny(denied: Vector[(FileChange, String)])
  case Ask(questions: Vector[Question], included: Vector[FileChange])

/** Default [[ChangeCollector]] implementing the §10.1 decision rules verbatim (E5).
  *
  * The five rules all reduce to `Deny` (rules 1–4) or `Allow`/`Ask` (rule 5), so the relative order among the four deny
  * rules changes only the *reason* reported, never the outcome. The one deliberate reordering: the outside-repo check
  * (rule 2) runs first as a guard so deny-pattern matching only ever sees a clean repo-relative path — matching a glob
  * against a path that escaped the repo with leading dot-dot segments would otherwise report a misleading deny-pattern
  * reason for a file that is really being denied for being outside the repo.
  */
final class DefaultChangeCollector extends ChangeCollector:

  override def classify(
      repoRoot: os.Path,
      changes: Vector[FileChange],
      config: StagingConfig
  ): IO[Classification] =
    IO {
      val specsRoot = new ForgePaths(repoRoot).specsRoot
      val verdicts: Vector[(FileChange, FileVerdict)] =
        changes.map(c => c -> verdictFor(repoRoot, specsRoot, config, c))

      val denied = verdicts.collect { case (c, FileVerdict.Deny(reason)) => c -> reason }
      if denied.nonEmpty then Classification.Deny(denied)
      else
        val asks = verdicts.collect { case (c, FileVerdict.Ask(q)) => c -> q }
        val allowed = verdicts.collect { case (c, FileVerdict.Allow) => c }
        if asks.nonEmpty then Classification.Ask(asks.map(_._2), allowed)
        else Classification.Allow(allowed)
    }

  private def verdictFor(
      repoRoot: os.Path,
      specsRoot: os.Path,
      config: StagingConfig,
      change: FileChange
  ): FileVerdict =
    // rule 2 (guard) — outside the repo root is always Deny; checking it first keeps glob matching off `../` paths.
    if !change.path.startsWith(repoRoot) then FileVerdict.Deny("outside repository root")
    else
      val rel = change.path.relativeTo(repoRoot)
      val relStr = rel.toString
      matchesAny(config.denyPatterns, relStr) match
        case Some(pattern) => FileVerdict.Deny(pattern) // rule 1
        case None =>
          if rel.segments.headOption.contains(".git") then FileVerdict.Deny("under .git/") // rule 3
          else if change.gitIgnored && !change.path.startsWith(specsRoot) then
            FileVerdict.Deny("ignored by .gitignore") // rule 4 (carve-out: the committed spec tree)
          else if !config.requireExplicitAllow then FileVerdict.Allow // rule 5 (lenient default)
          else if matchesAny(config.allowPatterns, relStr).isDefined then FileVerdict.Allow // rule 5 (strict, matched)
          else FileVerdict.Ask(askQuestion(relStr)) // rule 5 (strict, unmatched → Ask, never silently dropped)

  private def askQuestion(relStr: String): Question =
    Question(
      text =
        s"Stage '$relStr'? It is not covered by staging.allowPatterns and strict mode (requireExplicitAllow) is on.",
      options = Vector("Allow", "Deny"),
      allowFreeText = false,
      severity = QuestionSeverity.Blocking
    )

  /** First pattern in `patterns` that matches `relStr` (a `/`-separated repo-relative path), or `None`. */
  private def matchesAny(patterns: Vector[String], relStr: String): Option[String] =
    val path = Paths.get(relStr)
    patterns.find(pattern => compileMatchers(pattern).exists(_.matches(path)))

  /** Compiles a glob pattern into [[PathMatcher]]s.
    *
    * Java's glob treats a leading double-star-then-slash prefix as insisting on at least one directory segment before
    * the slash, so a pattern like that prefix followed by `.env` does NOT match a repo-root `.env` (only nested ones).
    * For any such double-star-slash-prefixed pattern we therefore also compile the prefix-stripped variant, so the
    * pattern matches the file at the root AND at any depth. Verified empirically against `java.nio.file.PathMatcher`
    * (design-rationale CC4).
    */
  private def compileMatchers(pattern: String): Vector[PathMatcher] =
    val fs = FileSystems.getDefault
    val base = fs.getPathMatcher("glob:" + pattern)
    if pattern.startsWith("**/") then Vector(base, fs.getPathMatcher("glob:" + pattern.drop(3)))
    else Vector(base)

/** Per-file outcome, aggregated by [[DefaultChangeCollector.classify]] into a single [[Classification]]. */
private enum FileVerdict:
  case Allow
  case Deny(reason: String)
  case Ask(question: Question)
