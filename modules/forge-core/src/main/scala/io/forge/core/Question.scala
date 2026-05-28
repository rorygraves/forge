package io.forge.core

import upickle.default.{readwriter, ReadWriter}

/** A question awaiting human input. Same shape regardless of the underlying delivery mechanism (§7.2 / §7.3) or origin
  * (a driver `AskUserQuestion` / halt envelope, or a Forge-internal gate such as the §10.1 ChangeCollector `Ask`).
  *
  * `defaultOption` is the option a Q&A pane should treat as the safe answer when the human gives none (timeout / no
  * input). Driver-originated questions carry `None` — the wire shape has no default and the human is expected to
  * answer. The §10.1 ChangeCollector `Ask` carries `Some("Deny")` so the safe-by-default posture (don't stage a
  * borderline path) is encoded structurally rather than left to option ordering. When set, it must be one of `options`.
  */
final case class Question(
    text: String,
    options: Vector[String],
    allowFreeText: Boolean,
    severity: QuestionSeverity,
    defaultOption: Option[String] = None
) derives ReadWriter

/** §6 — only `Blocking` forces a state transition into a *NeedsHumanInput state during design review (§11.2 step 11).
  */
enum QuestionSeverity:
  case Blocking
  case Clarifying
  case Optional

object QuestionSeverity:
  def fromString(s: String): Either[String, QuestionSeverity] = s.toLowerCase match
    case "blocking" => Right(QuestionSeverity.Blocking)
    case "clarifying" => Right(QuestionSeverity.Clarifying)
    case "optional" => Right(QuestionSeverity.Optional)
    case other => Left(s"unknown severity '$other'")

  extension (s: QuestionSeverity)
    def asString: String = s match
      case QuestionSeverity.Blocking => "blocking"
      case QuestionSeverity.Clarifying => "clarifying"
      case QuestionSeverity.Optional => "optional"

  given ReadWriter[QuestionSeverity] = readwriter[String].bimap(
    _.asString,
    s => fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  )
