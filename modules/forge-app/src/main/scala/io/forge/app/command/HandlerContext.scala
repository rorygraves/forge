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
  * **Deferred to Task 1.4.10:** [[StateChangingContext]] gains the constructed driver/reviewer connector and the
  * append-only action log (I2 steps 6 + 10) when the orchestrator handlers need them; the connector + log then join the
  * lock inside `Main`'s single `Resource` bracket. Task 1.4.9 holds only the lock so the bracket shape is real.
  */
final case class ReadOnlyContext(paths: ForgePaths, config: ForgeConfig, args: Vector[String])

final case class StateChangingContext(paths: ForgePaths, config: ForgeConfig, args: Vector[String])

final case class UnlockForceContext(paths: ForgePaths)
