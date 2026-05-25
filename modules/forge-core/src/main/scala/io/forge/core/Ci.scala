package io.forge.core

/** CI readiness policy (§8). */
enum CiPolicy:
  /** Default: branch-protection required set + observed-checks fallback. */
  case BranchProtectionThenObserved
  /** Skip CI gating entirely; logs `ci.skipped`. */
  case None

object CiPolicy:
  def fromString(s: String): Either[String, CiPolicy] = s match
    case "branch_protection_then_observed" => Right(CiPolicy.BranchProtectionThenObserved)
    case "none"                            => Right(CiPolicy.None)
    case other                             => Left(s"unknown CI policy '$other'")

  extension (p: CiPolicy)
    def asString: String = p match
      case CiPolicy.BranchProtectionThenObserved => "branch_protection_then_observed"
      case CiPolicy.None                         => "none"
