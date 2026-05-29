package io.forge.app.command

import io.forge.app.config.ForgeConfig
import io.forge.core.paths.ForgePaths

/** Task 1.4.9 I2 — per-command-class handler contexts.
  *
  * Three distinct types (not one `HandlerContext` with optional fields) so a read-only handler cannot reach for a
  * resource — a connector, the process lock — that its command class never constructs (I2 "separate context types per
  * class"). `Main` builds exactly the context its command class warrants and hands it to [[CommandRouter]].
  *
  * `args` carries the phase-1 `rest` tokens so a handler can finish the per-feature parse it owns (I2 step 8 — `forge
  * status [<feature>]`, `forge tail <feature>`). Task 1.4.9 ships shell handlers that don't consume it yet.
  *
  * **Task 1.4.10-d2c:** the orchestrator handlers (`forge run` / `forge new`) build the connector + action log + git/gh
  * stack on demand from `paths` + `config` via [[io.forge.app.orchestrator.OrchestratorBuilder]] inside `Main`'s lock
  * bracket, rather than threading them through this context. The connector holds no long-lived resource of its own (its
  * subprocesses are per-call `Resource`s) and `FileActionLog` is append-on-write, so [[StateChangingContext]] stays at
  * `(paths, config, args)` — no extra fields are needed.
  */
final case class ReadOnlyContext(paths: ForgePaths, config: ForgeConfig, args: Vector[String])

final case class StateChangingContext(paths: ForgePaths, config: ForgeConfig, args: Vector[String])

final case class UnlockForceContext(paths: ForgePaths)
