package io.forge.agents

import io.forge.core.Mode

/** §7.1 / §17 Slice 1 — call-site indirection over `Connector`.
  *
  * v1 keeps `Mode` (a two-case ADT) for config wiring, but orchestrator and connector callers speak to `Role` so the
  * Phase 3 role-trait refactor and the Phase 4 instance pivot don't have to sweep every call site. The smell test is in
  * `AGENTS.md`: pattern-matching on `Mode` outside `Mode` itself and `Role.pairFor` (the connector-construction site)
  * is a code smell — call `role.connector.<method>` instead.
  *
  * v1 ships two concrete cases; Phase 3 generalises these into full role traits (`Driver`, `Reviewer`, base `Agent`) —
  * see `roadmap.md` §4.2.
  */
sealed trait Role:
  def connector: Connector

object Role:
  final case class Driver(connector: Connector) extends Role
  final case class Reviewer(connector: Connector) extends Role

  /** Build the (driver, reviewer) pair for a feature's `Mode`. This is the one sanctioned `match m: Mode` site outside
    * `Mode` itself — every other caller routes through the returned `Role` values.
    */
  def pairFor(mode: Mode, claude: Connector, codex: Connector): (Driver, Reviewer) =
    mode match
      case Mode.ClaudeDriver => (Driver(claude), Reviewer(codex))
      case Mode.CodexDriver => (Driver(codex), Reviewer(claude))
