package io.forge.agents

/** §7.1 / §17 Slice 1 / roadmap §4.2 — the participant abstraction over `Connector`.
  *
  * `Agent` is the open base: a named participant in a feature run, backed by one `Connector`. The orchestrator and
  * connector callers speak to an `Agent` (via the concrete [[Role]] cases) rather than pattern-matching on `Mode`, so
  * adding a participant type does not sweep call sites. The smell test is in `AGENTS.md`: pattern-matching on `Mode`
  * outside `Mode` itself and the connector-construction seam (`ConnectorFactory`) is a code smell — resolve a `Mode` to
  * a `RolePairing` once (`io.forge.core.RolePairing.of`, design-3.5 Task 3.5.2) and call `agent.connector.<method>`
  * instead.
  *
  * v1 shipped two concrete cases (`Driver`, `Reviewer`); Slice 3.5 (roadmap §4.2) generalises them under this `Agent`
  * base so the Phase-3 sensors — and Phase-4/5 roles (PR-watcher, knowledge-base consultant) — are expressible as
  * further participants without touching `Mode`. The base is deliberately *not* sealed: a future role can extend
  * `Agent` directly. See `docs/design-3.5-role-trait.md`.
  */
trait Agent:
  /** The connector this participant drives its work through. */
  def connector: Connector

  /** Stable role tag, recorded where a run needs to name the participant (e.g. the action-log `actor` context). */
  def role: String

/** The built-in role family. `Driver` / `Reviewer` are the v1 pair; `Sensor` is the Phase-3 reviewer-side one-shot
  * surface (`profileRepo` / `classifyFailure` / `learnConventions`). All are `Agent`s; the family is closed because
  * these are Forge's own built-ins — a future *external* role extends [[Agent]] directly rather than this family.
  */
sealed trait Role extends Agent

object Role:
  final case class Driver(connector: Connector) extends Role:
    val role: String = "driver"

  final case class Reviewer(connector: Connector) extends Role:
    val role: String = "reviewer"

  /** The reviewer-side sensor surface (§7.11): the same connector the reviewer one-shots run on, named as the
    * participant that perceives the repo (`RepoProfiler` / `FailureClassifier` / `ConventionLearner`). Distinct `role`
    * tag so a run can attribute a sensor call apart from a code/design review. Not yet wired into the orchestrator
    * (that is design-3.5 carry-forward **C1**); its presence proves the family extends without a `Mode` edit.
    */
  final case class Sensor(connector: Connector) extends Role:
    val role: String = "sensor"
