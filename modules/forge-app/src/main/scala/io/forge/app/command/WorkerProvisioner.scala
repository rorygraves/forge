package io.forge.app.command

import cats.effect.IO
import io.forge.core.paths.ForgePaths
import io.forge.git.cli.{GitClient, GitError, RealGitClient}
import io.forge.instance.Instance

/** Task 4.2.2 — provision a daemon-spawned worker's **isolated working clone** (Phase-4 §10 O10 / §4.3) and its
  * re-rooted [[ForgePaths]] (B1).
  *
  * Given a worker id, the registered repo `source` checkout, and the resolved [[Instance]], this:
  *
  *   1. creates the per-worker root `instances/<name>/workers/<workerId>/` ([[Instance.workerRoot]]);
  *   1. `git clone`s `source` into `instances/<name>/workers/<workerId>/checkout/` ([[Instance.workerCheckout]]) — one
  *      fresh full working clone per worker (never the shared registered source, never a mutable mirror — O10);
  *   1. returns a `ForgePaths(repoRoot = checkout, localRootOpt = Some(workerRoot))` so the **committed** family
  *      (`.forge/specs/`, config, profile) anchors in the clone and merges back via PR, while the **local-runtime**
  *      family (the action log, state cache + poll baselines, lock) re-roots under the worker root *outside* the clone.
  *      Every `paths.xxx` consumer downstream — the orchestrator the worker runs in Task 4.2.3 — is unchanged; this is
  *      the B1 constructor swap, not a callsite sweep.
  *
  * The clone goes through the [[GitClient]] seam (`source`/`dest` absolute, so the client's cwd only needs to exist —
  * here the worker root the provisioner just created). The registered `source` is **input only**: it is never mutated,
  * preserving the isolation premise that a worker operates solely on its own clone (`forge-design-2.0.md` §4.3).
  *
  * Origin remapping (a local-path clone's `origin` points at the registered source, not the GitHub remote) is **not**
  * this task's concern: 4.2.2 proves the isolated clone + re-root against a local repo (no network); wiring the real
  * `Orchestrator` (which fetches/pushes/opens PRs) onto the clone is Task 4.2.3, and the supervisor that picks the
  * fetch source is 4.2.5.
  */
object WorkerProvisioner:

  /** Why provisioning a worker checkout failed. */
  enum WorkerProvisionError:
    /** The checkout dir already exists — a fresh clone would refuse (a stale worker dir, or a double-provision; the
      * idempotent re-spawn reconciliation is the supervisor's job in 4.2.5, so the provisioner refuses rather than
      * silently reusing or clobbering a tree).
      */
    case CheckoutExists(checkout: os.Path)

    /** `git clone` itself failed (source isn't a repo, disk full, …). */
    case CloneFailed(error: GitError)

    def message: String = this match
      case CheckoutExists(checkout) => s"worker checkout already exists: $checkout"
      case CloneFailed(error) => s"worker clone failed: ${error.message}"

  /** Provision worker `workerId`'s isolated checkout under `instance`, cloned from registered `source`.
    *
    * @param git
    *   factory from a cwd to a [[GitClient]] — defaults to [[RealGitClient]]; injectable so a supervisor unit test can
    *   stub the clone (the 4.2.2 provisioner test itself drives a real `git`).
    * @param home
    *   the per-user `~/.forge` anchor for the returned paths' reviewer assets / prices; defaults to `os.home`.
    */
  def provision(
      instance: Instance,
      workerId: String,
      source: os.Path,
      git: os.Path => GitClient = RealGitClient(_),
      home: os.Path = os.home
  ): IO[Either[WorkerProvisionError, ForgePaths]] =
    val workerRoot = instance.workerRoot(workerId)
    val checkout = instance.workerCheckout(workerId)
    IO.blocking(os.exists(checkout)).flatMap {
      case true => IO.pure(Left(WorkerProvisionError.CheckoutExists(checkout)))
      case false =>
        IO.blocking(os.makeDir.all(workerRoot)) >>
          git(workerRoot).clone(source, checkout).map {
            case Left(err) => Left(WorkerProvisionError.CloneFailed(err))
            case Right(()) => Right(new ForgePaths(checkout, home, localRootOpt = Some(workerRoot)))
          }
    }
