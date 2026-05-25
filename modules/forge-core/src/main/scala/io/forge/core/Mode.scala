package io.forge.core

import upickle.default.{ReadWriter, readwriter}

/** Driver/reviewer pairing for a feature. v1 supports two modes; mid-feature
  * switching is unsupported (§7, §1 non-goals). */
enum Mode:
  /** Claude drives spec/implementation/fix-up; Codex reviews. */
  case ClaudeDriver
  /** Codex drives spec/implementation/fix-up; Claude reviews. */
  case CodexDriver

object Mode:
  /** Manifest / config wire form. */
  def fromString(s: String): Either[String, Mode] = s match
    case "claude-driver" => Right(Mode.ClaudeDriver)
    case "codex-driver"  => Right(Mode.CodexDriver)
    case other =>
      Left(s"unknown mode '$other'; expected one of: claude-driver, codex-driver")

  extension (m: Mode)
    def asString: String = m match
      case Mode.ClaudeDriver => "claude-driver"
      case Mode.CodexDriver  => "codex-driver"

  given ReadWriter[Mode] = readwriter[String].bimap(
    _.asString,
    s => Mode.fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  )

/** Per-connector question delivery (§7.2/§7.3). Captured at connector
  * construction; never changes mid-session. */
enum QuestionMechanism:
  /** Mid-turn suspension primitive (Claude `AskUserQuestion`). */
  case Native
  /** Driver halts with structured JSON; orchestrator re-spawns (§7.3). */
  case HaltWithQuestion

/** Per-connector schema enforcement (§7.4/§7.5). */
enum SchemaMechanism:
  /** CLI-enforced output schema (Codex `--output-schema`). */
  case Native
  /** Prompt-and-validate with bounded 2-attempt cap (§7.5). */
  case SchemaFallback
