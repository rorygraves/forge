package io.forge.core

/** The two CLIs Forge orchestrates. Distinct from [[Mode]]: `Mode` is the persisted wire token that names a *pairing*;
  * `Cli` names a single concrete CLI a role's connector is built from. Connector construction and retry-block selection
  * key off `Cli`, never off `Mode` directly (design-3.5 Task 3.5.2).
  */
enum Cli:
  case Claude
  case Codex

/** Which CLI fills each role for a feature's [[Mode]] (roadmap §4.2 / design-3.5 Task 3.5.2).
  *
  * **Same-CLI story (D1, confirmed 2026-06-04).** What ships — and what C15 validated — is that one CLI both *drives*
  * and *reviews* a feature (Claude drives + reviews on a cheaper `haiku` reviewer model; Codex drives + reviews on its
  * single `-m`). So both fields resolve to the same `Cli` today. The earlier `Role.pairFor` cross-CLI shape ("the other
  * CLI reviews") was the stale one and is retired with this slice.
  *
  * The `driver` / `reviewer` fields are kept *distinct* deliberately: a future cross-CLI pairing (an independent
  * reviewer model) is then a change to [[RolePairing.of]] alone — callers already read the role-appropriate field
  * ([[io.forge.app.command.ProfileCommand]] reads `reviewer`; the orchestrator reads `driver`), so they need not
  * reshape. This is the §4.2 "configurations of role traits, not enum-case dispatch" idea made concrete.
  */
final case class RolePairing(driver: Cli, reviewer: Cli)

object RolePairing:
  /** The **single** `Mode → pairing` resolver. This is the one sanctioned `match Mode` outside `Mode` itself and the
    * connector-construction seam ([[io.forge.app.orchestrator.ConnectorFactory]]); every other site that used to match
    * `Mode` (the orchestrator's retry-budget selector, `forge profile`'s reviewer-side connector) now reads the
    * resolved pairing instead. Adding a pairing touches this one function.
    */
  def of(mode: Mode): RolePairing = mode match
    case Mode.ClaudeDriver => RolePairing(driver = Cli.Claude, reviewer = Cli.Claude)
    case Mode.CodexDriver => RolePairing(driver = Cli.Codex, reviewer = Cli.Codex)
