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
  * **Origin remap.** `git clone <local-path> <dest>` sets the clone's `origin` to the *local* source checkout, but the
  * v1 flow (`GitClient.push` → `origin`, then `gh` to open the PR) must target the registered repo's **real** (GitHub)
  * remote — otherwise the worker would push branches into the local source checkout and PR creation would fail. So the
  * provisioner reads the source's `origin` URL **before cloning** (the only step that can read it cleanly), then after
  * cloning re-points the clone's `origin` at it. A local-only source with no `origin` (e.g. a throwaway test repo) — or
  * one whose origin can't be read, which is also the non-repo case the clone below surfaces as `CloneFailed` — is left
  * as-is: there is no PR flow to target. The clone still benefits from git's local-object hardlinking on the initial
  * clone; only the *fetch/push URL* is remapped.
  *
  * **Failure leaves no half-provisioned tree.** A post-`makeDir` failure (clone *or* the post-clone set-url remap)
  * removes the freshly-created checkout before returning, so it never trips the [[WorkerProvisionError.CheckoutExists]]
  * guard on a retry for the same (still-unrecorded) worker id.
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

    /** Reading the source's `origin` or re-pointing the clone's `origin` at it failed (a real git error, not a missing
      * remote — a missing remote is a no-op leave-as-is). The clone exists but its push/PR target may be wrong, so the
      * provision is refused rather than handing back a misconfigured checkout.
      */
    case OriginRemapFailed(error: GitError)

    def message: String = this match
      case CheckoutExists(checkout) => s"worker checkout already exists: $checkout"
      case CloneFailed(error) => s"worker clone failed: ${error.message}"
      case OriginRemapFailed(error) => s"worker clone origin remap failed: ${error.message}"

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
          // Read the source's real remote *before* cloning, so the only fallible post-clone step is the set-url (whose
          // failure we then clean up after). An unreadable source origin — a local-only repo, or a non-repo source the
          // clone below will surface as CloneFailed — yields no remap rather than a separate failure.
          git(source).remoteUrl("origin").map(_.toOption.flatten).flatMap { originUrl =>
            git(workerRoot).clone(source, checkout).flatMap {
              case Left(err) => failAndCleanup(checkout, WorkerProvisionError.CloneFailed(err))
              case Right(()) =>
                applyOrigin(git, checkout, originUrl).flatMap {
                  case Right(()) => IO.pure(Right(new ForgePaths(checkout, home, localRootOpt = Some(workerRoot))))
                  case Left(err) => failAndCleanup(checkout, err)
                }
            }
          }
    }

  /** Re-point the fresh clone's `origin` at the source's real remote (read pre-clone). `None` (local-only / unreadable
    * source origin) is a no-op; a set-url failure surfaces as [[WorkerProvisionError.OriginRemapFailed]].
    */
  private def applyOrigin(
      git: os.Path => GitClient,
      checkout: os.Path,
      originUrl: Option[String]
  ): IO[Either[WorkerProvisionError, Unit]] =
    originUrl match
      case None => IO.pure(Right(()))
      case Some(url) =>
        git(checkout).setRemoteUrl("origin", url).map(_.left.map(WorkerProvisionError.OriginRemapFailed(_)))

  /** Remove the freshly-created `checkout` (best-effort) and return `error`, so a post-`makeDir` failure never leaves a
    * tree that trips the [[WorkerProvisionError.CheckoutExists]] guard on the next retry for the same worker id.
    */
  private def failAndCleanup(
      checkout: os.Path,
      error: WorkerProvisionError
  ): IO[Either[WorkerProvisionError, ForgePaths]] =
    IO.blocking(os.remove.all(checkout)).attempt.as(Left(error))
