package io.forge.daemon

import cats.effect.IO

/** Phase-4 §7 / O6 (credential isolation — the 4.3 blocker) — the seam the daemon's RPC handler uses to **broker
  * short-lived, host-isolated credentials** to a containerised worker over the worker control channel (Slice 4.3, Task
  * 4.3.3).
  *
  * The G4 safety claim is that a worker container never sees the host's home directory or the host-wide credentials.
  * The minimum ratified requirements (`forge-design-2.0.md` §7) are: **no host home mount** into the container; a
  * **per-repo / per-worker scoped `gh` token** (not the host-wide credential); and a **broker / short-lived-secret
  * injection model** for `claude`/`codex` auth so host-wide credentials never enter the container. This seam is the
  * broker: a containerised worker phones home over the mounted control socket (O9) and asks for exactly the secrets it
  * needs, which the host resolves *at request time* and returns — they are set in the worker process's own environment,
  * never baked into the container spec (where `docker inspect` would expose them) and never reachable via a home mount.
  *
  * The split mirrors [[Supervisor]]: forge-daemon owns the durable state + socket transport + the JSON-RPC method, but
  * does **not** know *where* a host's secrets live (a configured per-repo PAT env var, the host's API-key env vars).
  * That resolution — the [[io.forge.daemon]]-external policy + secret source — lives in `forge-app`, where the daemon
  * lifecycle composes; this trait is the narrow interface across that boundary. Defined here (not in `forge-app`) so
  * the handler can depend on it; kept to plain types so no forge-app type leaks down.
  *
  * The broker carries no durable state: a brokered credential is ephemeral (resolved live, handed over, never logged —
  * secrets must not land in the instance action log). It is therefore *not* a [[DaemonState]] writer, unlike the
  * supervisor's spawn effects.
  */
trait CredentialBroker:

  /** Resolve the short-lived credentials worker `workerId` needs to operate on its registered `repo`: a scoped `gh`
    * token plus whatever `claude`/`codex` auth the host has configured. Returns the env entries the worker sets in its
    * own process environment, or a [[BrokerError]] the handler maps to a JSON-RPC error.
    *
    * Refuses (rather than silently falling back to a host-wide credential) when a *required* secret is not configured —
    * the whole point of O6 is that the absence of a scoped token is a loud failure, not a quiet downgrade to the host's
    * broad `gh` login.
    */
  def brokerFor(workerId: String, repo: String): IO[Either[BrokerError, BrokeredCredentials]]

object CredentialBroker:

  /** A broker that refuses to broker — the 4.1/4.2 daemon that runs no containerised workers (and the default for
    * `serveUntilShutdown` callers that wire no broker, e.g. the in-process test daemons). Every `brokerFor` answers
    * with the reason so the handler returns a clean error rather than a `MethodNotFound`.
    */
  val noop: CredentialBroker = new CredentialBroker:
    def brokerFor(workerId: String, repo: String): IO[Either[BrokerError, BrokeredCredentials]] =
      IO.pure(
        Left(BrokerError.Unavailable("this daemon has no credential broker (credential brokering is unavailable)"))
      )

/** The credentials a [[CredentialBroker]] hands to a worker (Task 4.3.3) — the short-lived secrets it injects into its
  * own process environment so the `gh` CLI and the `claude`/`codex` connectors authenticate without a host home mount.
  *
  *   - `env` — the environment entries to set (keyed by the canonical names the tools read: [[GhTokenKey]],
  *     [[AnthropicKeyKey]], [[OpenAiKey]]). These are the secret *values*; they cross the control socket but never the
  *     instance log and never the container spec.
  *   - `missing` — the canonical names of *optional* credentials the host had **not** configured (an agent auth key
  *     absent on the host). Non-secret, for observability: the worker surfaces a clear "this driver's auth was not
  *     brokered" rather than failing opaquely deep in a connector spawn. A *required* secret's absence is a
  *     [[BrokerError]], not a `missing` entry.
  */
final case class BrokeredCredentials(env: Map[String, String], missing: Vector[String] = Vector.empty)

object BrokeredCredentials:

  /** The env var the `gh` CLI reads for its auth token (`RealGhClient`'s `env` overlay sets this). The broker injects a
    * scoped per-repo/per-worker token here — never the host-wide `gho_` login.
    */
  val GhTokenKey: String = "GH_TOKEN"

  /** The env var the Claude connector authenticates with when no logged-in OAuth session is present (the container has
    * no host `~/.claude`).
    */
  val AnthropicKeyKey: String = "ANTHROPIC_API_KEY"

  /** The env var the Codex connector authenticates with when no logged-in session is present (the container has no host
    * `~/.codex`).
    */
  val OpenAiKey: String = "OPENAI_API_KEY"

/** Why credential brokering failed (Task 4.3.3). Recoverable from the handler's perspective (a clean JSON-RPC error),
  * not a connection tear-down.
  */
enum BrokerError:

  /** This daemon has no broker wired (the control-only / pre-container daemon). */
  case Unavailable(reason: String)

  /** A *required* secret (the scoped `gh` token) is not configured on the host — `sourceEnvVar` is the host env var the
    * policy expected it in. Refused rather than falling back to the host-wide credential (O6).
    */
  case MissingRequiredSecret(sourceEnvVar: String)

  def message: String = this match
    case Unavailable(reason) => reason
    case MissingRequiredSecret(sourceEnvVar) =>
      s"required credential is not configured: host env var '$sourceEnvVar' is unset or empty; " +
        "refusing to fall back to a host-wide credential (O6)"
