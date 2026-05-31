package io.forge.agents

import cats.effect.IO

/** Opt-in raw-capture sink for **driver** CLI sessions — the streaming analogue of the reviewer-only one-shot dump
  * (`FORGE_REVIEWER_RAW_DUMP_DIR`, [[ClaudeConnector.dumpRawEnvelope]]).
  *
  * A reviewer call produces a single `--output-format json` envelope, so the reviewer dump overwrites one `.json` file
  * per call. A driver session instead streams NDJSON over many turns, so this sink **appends** every raw stdout line to
  * a single `<connector>-<label>-<uuid>.jsonl` file. That makes "what did the implement driver actually do for \$9.56"
  * (mvp-friction gap #10) answerable offline, instead of only off the live CLI.
  *
  * Same discipline as the reviewer dump:
  *   - **Default off.** [[driver]] reads `FORGE_DRIVER_RAW_DUMP_DIR` and returns `None` when it is unset, so there is
  *     no steady-state cost and the wiring is safe to leave in (the "default-on runtime <60s" rule — this is a debug
  *     sink, not a steady-state feature).
  *   - **Best effort.** A write failure is swallowed (`.attempt.void`); a dump error never fails the driver session.
  *
  * The session id is deliberately **not** in the filename: it is not known until the first `Init` event arrives, by
  * which point lines are already flowing. It is recoverable from the file's first line (the init envelope). `<label>`
  * is the driver phase the connector spawned (`spec` / `spec-resume` / `implement` / `fixup`).
  */
object RawDumpSink:

  /** Env var gating the driver-session dump. When set to a directory, every driver session writes its raw stdout NDJSON
    * stream there. Parallel to (and independent of) the reviewer-only `FORGE_REVIEWER_RAW_DUMP_DIR`.
    */
  val DriverEnvVar = "FORGE_DRIVER_RAW_DUMP_DIR"

  /** Env-gated per-session sink. Returns `None` (no overhead, no file) unless [[DriverEnvVar]] is set; otherwise
    * delegates to [[sinkTo]] with the configured directory. The `IO` exists because reading the env, creating the
    * directory, and minting the per-session filename are all effects performed **once** per session.
    */
  def driver(connectorName: String, label: String): IO[Option[String => IO[Unit]]] =
    IO.delay(sys.env.get(DriverEnvVar)).flatMap {
      case None => IO.pure(None)
      case Some(dir) => sinkTo(os.Path(dir, os.pwd), connectorName, label).map(Some(_))
    }

  /** Build a per-session append sink writing under `dir`. Resolves the target file **once** (creating the directory and
    * minting a fresh UUID name), then returns a closure that appends each non-blank line as one NDJSON record. Blank
    * lines are skipped: the fs2 line splitter emits a trailing empty segment at EOF and it carries no envelope.
    *
    * Separated from [[driver]] so the file mechanics are unit-testable without manipulating the process environment
    * (`sys.env` is immutable at runtime).
    */
  def sinkTo(dir: os.Path, connectorName: String, label: String): IO[String => IO[Unit]] =
    IO.blocking {
      os.makeDir.all(dir)
      val target = dir / s"$connectorName-$label-${java.util.UUID.randomUUID()}.jsonl"
      (line: String) =>
        if line.trim.isEmpty then IO.unit
        else IO.blocking(os.write.append(target, line + "\n")).attempt.void
    }
