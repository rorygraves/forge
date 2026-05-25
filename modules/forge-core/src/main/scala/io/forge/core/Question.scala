package io.forge.core

/** A driver-originated question awaiting human input. Same shape regardless of the underlying delivery mechanism (§7.2
  * / §7.3).
  */
final case class Question(
    text: String,
    options: Vector[String],
    allowFreeText: Boolean,
    severity: QuestionSeverity
)

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
