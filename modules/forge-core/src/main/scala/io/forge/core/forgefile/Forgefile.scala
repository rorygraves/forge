package io.forge.core.forgefile

import io.forge.core.profile.RepoProfile
import upickle.default.ReadWriter

/** Phase-4 §7 / O1 (tool pinning, ruled — flipped) — the **normative `Forgefile`** (Slice 4.3, Task 4.3.2).
  *
  * The committed, human-authored source of truth for **what image a worker's container runs and which tool versions it
  * pins** (reproducibility *policy*, per repo). It is a peer to a `Dockerfile`/devcontainer — a top-level descriptor at
  * the repo root, read (never written) by Forge. The supervisor (`ContainerSpawner`, Task 4.3.4) resolves [[image]]
  * into the `ContainerSpec` it launches.
  *
  * **Normative, not perceived (the O1 ruling).** The [[RepoProfile]] (Phase-3, observed/adaptive state) *discovers
  * defaults and validates* the pinned set against what it perceives ([[Forgefile.validate]]), but is **not** the source
  * of truth: its job is perception, not normative pinning. So a profiler/Forgefile mismatch is a non-fatal **warning**,
  * never an error — the Forgefile wins.
  *
  * Wire format — a minimal, `Dockerfile`-flavoured directive grammar (one directive per line, parsed by
  * [[Forgefile.parse]]):
  * {{{
  *   # comments and blank lines are ignored
  *   image eclipse-temurin:21-jdk    # exactly one — the OCI image reference
  *   tool sbt 1.10.7                  # zero or more — a pinned <name> <version>
  *   tool scala 3.7.1
  * }}}
  */
final case class Forgefile(image: String, tools: Vector[ToolPin]) derives ReadWriter:
  /** The pinned version of `name`, if the Forgefile pins one. */
  def tool(name: String): Option[ToolPin] = tools.find(_.name == name)

/** One pinned tool: a `name` (the executable, e.g. `sbt`) and the `version` the worker container must provide. */
final case class ToolPin(name: String, version: String) derives ReadWriter

object Forgefile:

  /** Parse the text of a `Forgefile` (the [[Forgefile]] directive grammar) into a model, or `Left` with a line-numbered
    * diagnostic on the first malformed line. Fail-fast (not error-accumulating) — a committed source of truth that is
    * malformed at all is a single thing to fix, and the line number points at it.
    *
    * Rules: `#`-prefixed and blank lines are ignored; `image <ref>` must appear **exactly once**; `tool <name>
    * <version>` may repeat but a duplicate tool name is rejected (an ambiguous pin); any other leading token, or a
    * directive with the wrong argument count, is an error.
    */
  def parse(text: String): Either[String, Forgefile] =
    val numbered = text.linesIterator.zipWithIndex.map((line, i) => (line.trim, i + 1))
    var image: Option[String] = None
    val tools = scala.collection.mutable.ListBuffer.empty[ToolPin]
    val seen = scala.collection.mutable.Set.empty[String]

    val parseError: Option[String] = numbered
      .filter((line, _) => line.nonEmpty && !line.startsWith("#"))
      .map { (line, n) =>
        line.split("\\s+").toVector match
          case "image" +: rest =>
            rest match
              case Vector(ref) if image.isEmpty =>
                image = Some(ref)
                None
              case Vector(_) => Some(s"Forgefile line $n: 'image' declared more than once")
              case other => Some(s"Forgefile line $n: 'image' takes exactly one argument, got ${other.size}")
          case "tool" +: rest =>
            rest match
              case Vector(name, version) =>
                if !seen.add(name) then Some(s"Forgefile line $n: tool '$name' pinned more than once")
                else
                  tools += ToolPin(name, version)
                  None
              case other =>
                Some(s"Forgefile line $n: 'tool' takes a <name> and a <version>, got ${other.size} argument(s)")
          case other =>
            Some(s"Forgefile line $n: unknown directive '${other.headOption.getOrElse("")}'")
      }
      .collectFirst { case Some(err) => err }

    parseError match
      case Some(err) => Left(err)
      case None =>
        image match
          case Some(ref) => Right(Forgefile(ref, tools.toVector))
          case None => Left("Forgefile declares no 'image'")

  /** Validate a Forgefile's pinned set against the Phase-3 [[RepoProfile]]'s perceptions (the O1 discover-and-validate
    * hook). **Perception, not pinning**: the Forgefile is the normative source of truth, so every mismatch is a
    * non-fatal warning, never an error — this surfaces drift between what the profiler perceives the repo *uses* and
    * what the repo *pins*, so a container missing a needed tool is flagged before a worker runs blind.
    *
    * The perceived tool set = the build tool plus the head executable of every gate command's argv; each one not pinned
    * in the Forgefile yields one warning. Returns empty when every perceived tool is pinned.
    */
  def validate(forgefile: Forgefile, profile: RepoProfile): Vector[String] =
    val pinned = forgefile.tools.map(_.name).toSet
    val perceived = (profile.buildTool +: profile.commands.flatMap(_.argv.headOption)).distinct
    perceived
      .filterNot(pinned.contains)
      .map(t => s"profiler perceives tool '$t' in use but the Forgefile pins no matching version")
