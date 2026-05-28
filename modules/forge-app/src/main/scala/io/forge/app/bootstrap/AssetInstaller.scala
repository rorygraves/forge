package io.forge.app.bootstrap

import cats.effect.IO
import io.forge.core.paths.ForgePaths

/** Slice 4 PR-A — installs the reviewer assets (JSON schemas, system-prompt files, PR/decomposition/answer templates)
  * from the forge-app classpath into the user's `~/.forge/{schemas,prompts,templates}/` on first run.
  *
  * The in-tree source for the shipped assets lives at `assets/reviewer/{schemas,prompts}/...` and
  * `assets/templates/...` at the repo root; `forge-app`'s sbt build adds that directory as an unmanaged resource root
  * so the leaves appear on the classpath at the same relative paths.
  *
  * **Skip-if-exists is the safety contract.** Once a file lives under `~/.forge/`, the operator may have customised it
  * (tighter schema, project-specific prompt). The installer never overwrites; it only fills in missing files.
  *
  * Errors:
  *   - Missing classpath resource — surfaces [[AssetInstaller.MissingResource]]. Should be unreachable in a healthy
  *     build; flagged loudly so a refactor that drops `assets/` from forge-app's classpath fails fast.
  *   - Filesystem write failure — surfaces [[AssetInstaller.WriteFailed]]. The first failure aborts the install (the
  *     short-circuit is intentional — partial installs against a permission-denied user are easier to recover from than
  *     a half-installed `~/.forge/`).
  *   - Existing destination is not a regular file (directory, FIFO, …) — surfaces
  *     [[AssetInstaller.InvalidExistingDestination]] rather than silent `Skipped`, so the operator finds out at install
  *     time instead of when the reviewer connector later fails to read it.
  */
object AssetInstaller:

  /** Per-asset record of what the installer did. Returned from [[installIfMissing]] so the bootstrap can log a one-line
    * summary per asset.
    */
  final case class InstalledAsset(source: String, dest: os.Path, action: Action)

  enum Action:
    case Installed
    case Skipped

  /** Typed failure for the [[installIfMissing]] Either channel. */
  sealed trait Error:
    def detail: String

  final case class MissingResource(resourcePath: String) extends Error:
    def detail: String = s"reviewer asset not found on classpath: $resourcePath"

  final case class WriteFailed(dest: os.Path, cause: Throwable) extends Error:
    def detail: String = s"failed to write $dest: ${cause.getMessage}"

  /** The destination path exists but isn't a regular file (directory, FIFO, broken symlink, etc.). Reading it as a file
    * downstream would fail; reporting `Skipped` would falsely claim a healthy install. The connector that reads this
    * path next would surface a generic IO error well away from the cause — better to fail loudly here.
    */
  final case class InvalidExistingDestination(dest: os.Path, kind: String) extends Error:
    def detail: String =
      s"refusing to skip $dest — exists but is not a regular file (kind=$kind); remove or replace it manually"

  /** First-run installer. Reads each shipped asset from the classpath and writes it to the matching location under the
    * user's `~/.forge/`. Existing destination files are left untouched.
    *
    * Idempotent: calling twice produces the same result the second time (every asset reported as `Skipped`).
    *
    * Parent directories are created if missing.
    */
  def installIfMissing(paths: ForgePaths): IO[Either[Error, Vector[InstalledAsset]]] =
    val sources = ShippedAssets.all(paths)
    IO.blocking(installSync(sources))

  private def installSync(sources: Vector[ShippedAsset]): Either[Error, Vector[InstalledAsset]] =
    val acc = Vector.newBuilder[InstalledAsset]
    val it = sources.iterator
    var failure: Option[Error] = None
    while it.hasNext && failure.isEmpty do
      val asset = it.next()
      installOne(asset) match
        case Right(rec) => acc += rec
        case Left(err) => failure = Some(err)
    failure match
      case Some(err) => Left(err)
      case None => Right(acc.result())

  private def installOne(asset: ShippedAsset): Either[Error, InstalledAsset] =
    // os.exists alone treats a directory or FIFO at the leaf as "present", which would have the installer
    // claim success while the connector that reads the leaf as a file later fails with a generic IOException
    // well removed from the cause. Tightening to "exists AND is a regular file" surfaces the misconfiguration
    // here, as a typed error the bootstrap can render at install time.
    if os.exists(asset.dest) then
      if os.isFile(asset.dest) then Right(InstalledAsset(asset.resourcePath, asset.dest, Action.Skipped))
      else
        val kind =
          if os.isDir(asset.dest) then "directory"
          else if os.isLink(asset.dest) then "symlink"
          else "non-file"
        Left(InvalidExistingDestination(asset.dest, kind))
    else
      readResource(asset.resourcePath) match
        case None => Left(MissingResource(asset.resourcePath))
        case Some(bytes) =>
          try
            os.makeDir.all(asset.dest / os.up)
            os.write(asset.dest, bytes)
            Right(InstalledAsset(asset.resourcePath, asset.dest, Action.Installed))
          catch case t: Throwable => Left(WriteFailed(asset.dest, t))

  private def readResource(path: String): Option[Array[Byte]] =
    val cl = getClass.getClassLoader
    val stream = cl.getResourceAsStream(path)
    if stream eq null then None
    else
      try Some(stream.readAllBytes())
      finally stream.close()

  /** A single shipped asset — `(classpath resource path, on-disk destination)`. The destination is resolved from
    * [[ForgePaths]] (the seam discipline rules out raw `.forge/...` literals outside that helper).
    */
  private final case class ShippedAsset(resourcePath: String, dest: os.Path)

  private object ShippedAssets:

    /** Schemas the §7.1 reviewer connectors bind against (Claude `--json-schema`, Codex `--output-schema`). */
    val SchemaLeaves: Vector[String] = Vector(
      "design-review.json",
      "code-review.json",
      "refine.json"
    )

    /** System-prompt files — six entries, one per reviewer-method × per-CLI. The driver-side prompts
      * (`specify.<cli>.md`, `implement.<cli>.md`, `fixup.<cli>.md`) land in a later sub-PR; A1 ships only the reviewer
      * prompts.
      */
    val PromptLeaves: Vector[String] = Vector(
      "design-review.claude.md",
      "design-review.codex.md",
      "code-review.claude.md",
      "code-review.codex.md",
      "refine.claude.md",
      "refine.codex.md"
    )

    /** Templates per §11.4 / §7.7 / §14.3. The round-1 / attempt-1 instances stand in for the round-N / attempt-N
      * family; the orchestrator (Slice 4B PR-J) parameterises the leaf name when it actually renders.
      */
    val TemplateLeaves: Vector[String] = Vector(
      "pr-body.md.hbs",
      "decomposition.md.hbs",
      "spec-answers.md.hbs",
      "design-review-r1-answers.md.hbs",
      "design-pr-feedback-r1-answers.md.hbs",
      "impl-answers.md.hbs",
      "fixup-r1-answers.md.hbs"
    )

    def all(paths: ForgePaths): Vector[ShippedAsset] =
      SchemaLeaves.map(leaf => ShippedAsset(s"reviewer/schemas/$leaf", paths.userSchemasDir / leaf)) ++
        PromptLeaves.map(leaf => ShippedAsset(s"reviewer/prompts/$leaf", paths.userPromptsDir / leaf)) ++
        TemplateLeaves.map(leaf => ShippedAsset(s"templates/$leaf", paths.userTemplatesDir / leaf))
