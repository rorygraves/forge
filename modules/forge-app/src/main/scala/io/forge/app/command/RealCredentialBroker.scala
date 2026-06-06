package io.forge.app.command

import cats.effect.IO
import io.forge.daemon.{BrokerError, BrokeredCredentials, CredentialBroker}

/** Task 4.3.3 — the real credential broker (`forge-app`'s implementation of the [[CredentialBroker]] O6 seam).
  *
  * Resolves the short-lived, host-isolated credentials a containerised worker needs **without** mounting the host home
  * or handing over a host-wide credential:
  *
  *   - **`gh` token (required).** A *configured per-repo* token, sourced from the host env var the [[CredentialPolicy]]
  *     names for the repo ([[CredentialPolicy.ghTokenEnvFor]]) — a per-repo PAT, never the host-wide `gho_` login. If
  *     the configured env var is unset/empty the broker **refuses** ([[BrokerError.MissingRequiredSecret]]) rather than
  *     falling back to `gh auth token`: the absence of a scoped token must be a loud failure (O6), not a silent
  *     downgrade.
  *   - **`claude` / `codex` auth (optional, best-effort).** The host's configured API keys
  *     ([[CredentialPolicy.anthropicKeyEnv]] / [[CredentialPolicy.openaiKeyEnv]]), injected as the canonical env vars
  *     the connectors read ([[BrokeredCredentials.AnthropicKeyKey]] / [[BrokeredCredentials.OpenAiKey]]). A container
  *     has no host `~/.claude` / `~/.codex` OAuth session, so an API key is how the driver authenticates inside it. An
  *     *absent* key is reported in `missing` (the worker surfaces "this driver's auth was not brokered") rather than
  *     refused — the broker does not know which driver this worker will run, so it brokers what it has and leaves the
  *     required-for-this-mode check to the worker. (Keychain-OAuth brokering is a carry-forward; see design-4.3.)
  *
  * Secrets are read from a [[SecretSource]] (default: the daemon process environment) at request time and returned over
  * the worker control channel — they never touch the instance log or the container spec. The brokered values are set in
  * the worker's *own* process environment (4.3.4), so `docker inspect` never exposes them and no home mount is needed.
  *
  * Mirrors the [[RealSupervisor]] split: the [[CredentialBroker]] trait + wire types live in forge-daemon (so the
  * handler can depend on them); the policy + secret resolution live here, where the daemon lifecycle composes.
  */
final class RealCredentialBroker(policy: CredentialPolicy, secrets: SecretSource) extends CredentialBroker:

  def brokerFor(workerId: String, repo: String): IO[Either[BrokerError, BrokeredCredentials]] =
    val ghEnvVar = policy.ghTokenEnvFor(repo)
    for
      gh <- secrets.get(ghEnvVar)
      anthropic <- secrets.get(policy.anthropicKeyEnv)
      openai <- secrets.get(policy.openaiKeyEnv)
    yield gh match
      case None => Left(BrokerError.MissingRequiredSecret(ghEnvVar))
      case Some(token) =>
        val env = Map(BrokeredCredentials.GhTokenKey -> token) ++
          anthropic.map(BrokeredCredentials.AnthropicKeyKey -> _) ++
          openai.map(BrokeredCredentials.OpenAiKey -> _)
        val missing = Vector(
          Option.when(anthropic.isEmpty)(BrokeredCredentials.AnthropicKeyKey),
          Option.when(openai.isEmpty)(BrokeredCredentials.OpenAiKey)
        ).flatten
        Right(BrokeredCredentials(env, missing))

object RealCredentialBroker:

  /** The default broker the daemon wires (Task 4.3.3): the §18 default [[CredentialPolicy]] resolving secrets from the
    * daemon process environment. The daemon host is where the configured PAT / API-key env vars live.
    */
  val default: CredentialBroker = new RealCredentialBroker(CredentialPolicy.Default, SecretSource.env)

/** Where a [[RealCredentialBroker]] reads secret *values* from (Task 4.3.3). Abstracted so a unit test injects a fixed
  * map instead of mutating real process env, and so a future source (a secrets manager, the macOS keychain) slots in
  * behind the same seam. An empty value reads as absent (a set-but-empty env var is "not configured").
  */
trait SecretSource:
  def get(name: String): IO[Option[String]]

object SecretSource:

  /** Reads from the daemon process environment (`sys.env`). A missing or empty value is `None`. */
  val env: SecretSource = new SecretSource:
    def get(name: String): IO[Option[String]] = IO.delay(sys.env.get(name).filter(_.nonEmpty))

  /** A fixed-map source for tests. A missing or empty value is `None`. */
  def fixed(values: Map[String, String]): SecretSource = new SecretSource:
    def get(name: String): IO[Option[String]] = IO.pure(values.get(name).filter(_.nonEmpty))

/** Which host env var holds each brokered secret (Task 4.3.3) — the *names*, never the values, so this is safe to carry
  * in (later-slice) instance config without persisting a secret.
  *
  *   - `ghTokenEnv` — the default env var holding the `gh` token (a per-repo PAT). Default [[DefaultGhTokenEnv]].
  *   - `ghTokenEnvByRepo` — per-repo overrides keyed by the registered repo source path, so different repos can carry
  *     different scoped PATs (the "configured per-repo PAT" posture). Falls back to `ghTokenEnv`.
  *   - `anthropicKeyEnv` / `openaiKeyEnv` — the env vars holding the agent API keys. Default to the canonical names the
  *     SDKs/CLIs themselves read, so the common case is a straight pass-through of an already-set key.
  */
final case class CredentialPolicy(
    ghTokenEnv: String = CredentialPolicy.DefaultGhTokenEnv,
    ghTokenEnvByRepo: Map[String, String] = Map.empty,
    anthropicKeyEnv: String = BrokeredCredentials.AnthropicKeyKey,
    openaiKeyEnv: String = BrokeredCredentials.OpenAiKey
):

  /** The host env var the `gh` token for `repo` is read from: the per-repo override if present, else the default. */
  def ghTokenEnvFor(repo: String): String = ghTokenEnvByRepo.getOrElse(repo, ghTokenEnv)

object CredentialPolicy:

  /** The default env var a per-repo `gh` PAT is read from. A Forge-namespaced name (not `GH_TOKEN`) so a worker's
    * brokered token is sourced from an explicitly-provisioned variable rather than accidentally inheriting the host's.
    */
  val DefaultGhTokenEnv: String = "FORGE_GH_TOKEN"

  /** The §18-default policy: the default gh-token env var, the canonical agent-key env vars, no per-repo overrides. */
  val Default: CredentialPolicy = CredentialPolicy()
