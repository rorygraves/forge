package io.forge.specs

import io.forge.core.PieceId
import io.forge.core.manifest.{Manifest, ManifestPatch, ManifestPatchOp}

/** Task 1.4.13 **M6** / §5.4 — the parse half of `forge reconcile`: read operator edits made in `decomposition.md`'s
  * editable regions ([[DocSync]] / §5.3) back into a [[ManifestPatch]].
  *
  * This object is **pure** — it never touches the template or disk. The `forge reconcile` handler
  * (`io.forge.app.command.ReconcileCommand`) supplies the rendered strings and owns the round-trip verification that
  * proves the on-disk diff is *fully* explained by reconcilable edits:
  *
  *   1. render the current manifest → `canonical` (via [[DocSync.renderManifest]]); 2. read `decomposition.md` from
  *      disk → `onDisk`; 3. if `canonical == onDisk` there are no edits (NoChange — the handler short-circuits); 4.
  *      otherwise [[parse]] `onDisk`'s order region into `(order, summaries)`, [[buildPatch]] the diff against the
  *      manifest, apply it to get a candidate manifest `M'`, render `M'` and check it equals `onDisk`.
  *
  * Step 4's render-and-compare is the safety net: only editable-region content (piece summaries via the
  * `forge:editable-summary` markers; piece ordering via the list order) can be reproduced by re-rendering, so a
  * candidate render that matches `onDisk` byte-for-byte means every difference was an editable-region edit. Any edit to
  * a non-editable region (piece id/title, status badge, the header/footer prose, the markers themselves) leaves a
  * residual the candidate render can't reproduce, and the handler refuses with the offending hunks (§5.4).
  */
object Reconcile:

  /** The editable-region content extracted from an on-disk `decomposition.md`: the piece ordering (from the list order
    * inside `forge:order-start`/`-end`) and each piece's `forge:editable-summary` text.
    */
  final case class Parsed(order: Vector[PieceId], summaries: Map[PieceId, String])

  /** Structural problems that make `decomposition.md` non-reconcilable before any diffing — the order markers are
    * missing/duplicated, or a `forge:piece` marker names an id that isn't a valid `p<n>`. These mean the file was
    * edited outside the reconcilable regions (or hand-replaced), so §5.4 directs the operator to edit `manifest.json`
    * directly.
    */
  final case class ParseError(message: String)

  private val OrderStart = "<!-- forge:order-start -->"
  private val OrderEnd = "<!-- forge:order-end -->"
  private val PieceMarker = """<!--\s*forge:piece\s+(\S+)\s*-->""".r
  private val SummaryOpen = """<!--\s*forge:editable-summary\s+(\S+)\s*-->""".r
  private val SummaryClose = "<!-- /forge:editable-summary -->"

  /** The 3-space indent the `decomposition.md.hbs` template adds to summary lines (` {{this.summary}}`). Parsing is the
    * exact inverse: strip this indent off each summary line so an unedited summary round-trips identically.
    */
  private val SummaryIndent = "   "

  /** Parse the editable regions of an on-disk `decomposition.md`. Line-based on the markers DocSync renders. The
    * summary de-indent is the exact inverse of the template's indent, so an unedited file's parsed summaries equal the
    * manifest's (and re-rendering reproduces the file). A summary the operator re-indented or made multi-line in a way
    * the single-line template can't reproduce simply fails the handler's round-trip check (safe refuse), never a silent
    * corrupt write.
    */
  def parse(onDisk: String): Either[ParseError, Parsed] =
    val lines = onDisk.linesWithSeparators.map(_.stripLineEnd).toVector
    val starts = lines.indices.filter(i => lines(i).contains(OrderStart)).toVector
    val ends = lines.indices.filter(i => lines(i).contains(OrderEnd)).toVector
    (starts, ends) match
      case (Vector(s), Vector(e)) if s < e =>
        parseRegion(lines.slice(s + 1, e))
      case _ =>
        Left(
          ParseError(
            s"decomposition.md is missing exactly one '$OrderStart' / '$OrderEnd' pair (found " +
              s"${starts.size} start / ${ends.size} end markers). The editable order region was removed or " +
              "duplicated — edit manifest.json directly, then re-run."
          )
        )

  private def parseRegion(region: Vector[String]): Either[ParseError, Parsed] =
    val order = scala.collection.mutable.ArrayBuffer.empty[PieceId]
    val summaries = scala.collection.mutable.LinkedHashMap.empty[PieceId, String]
    var i = 0
    var error: Option[ParseError] = None
    while i < region.length && error.isEmpty do
      val line = region(i)
      PieceMarker.findFirstMatchIn(line) match
        case Some(m) =>
          PieceId.fromString(m.group(1)) match
            case Left(msg) => error = Some(ParseError(s"forge:piece marker names an invalid piece id: $msg"))
            case Right(pid) => order += pid
          i += 1
        case None =>
          SummaryOpen.findFirstMatchIn(line) match
            case Some(m) =>
              PieceId.fromString(m.group(1)) match
                case Left(msg) =>
                  error = Some(ParseError(s"forge:editable-summary marker names an invalid piece id: $msg"))
                  i += 1
                case Right(pid) =>
                  val close = region.indexWhere(_.contains(SummaryClose), i + 1)
                  if close < 0 then
                    error = Some(ParseError(s"forge:editable-summary for '${pid.value}' has no closing marker"))
                    i = region.length
                  else
                    summaries.update(pid, deindent(region.slice(i + 1, close)))
                    i = close + 1
            case None => i += 1
    error.toLeft(Parsed(order.toVector, summaries.toMap))

  /** Strip the template's leading [[SummaryIndent]] off each line (where present) and rejoin. The exact inverse of the
    * template's ` {{this.summary}}` so an unedited single-line summary round-trips.
    */
  private def deindent(lines: Vector[String]): String =
    lines.map(l => if l.startsWith(SummaryIndent) then l.drop(SummaryIndent.length) else l).mkString("\n")

  /** Diff the parsed editable-region content against the manifest into a [[ManifestPatch]]:
    *
    *   - a per-piece `EditPiece(summary)` for every piece whose parsed summary differs from the manifest's, in manifest
    *     order (deterministic);
    *   - a single `ReorderPieces(parsed.order)` when the parsed order differs from the manifest order.
    *
    * EditPiece ops come first so they reference ids by a stable order; the final `ReorderPieces` (if any) permutes. The
    * caller validates the result against [[ManifestPatch.validate]] — that is where merged-piece protection (§5.1) and
    * the §5.5 merged-prefix invariant are enforced, with op-indexed messages. A parsed order that adds or drops piece
    * ids (not a permutation) is caught by the handler before this, with a friendlier message.
    */
  def buildPatch(manifest: Manifest, parsed: Parsed): ManifestPatch =
    val edits: Vector[ManifestPatchOp] =
      manifest.pieces.flatMap { p =>
        parsed.summaries.get(p.id).filter(_ != p.summary).map { newSummary =>
          ManifestPatchOp.EditPiece(
            id = p.id,
            title = None,
            summary = Some(newSummary),
            specPath = None,
            acceptanceHash = None
          )
        }
      }
    val currentOrder = manifest.pieces.map(_.id)
    val reorder: Vector[ManifestPatchOp] =
      if parsed.order == currentOrder then Vector.empty
      else Vector(ManifestPatchOp.ReorderPieces(parsed.order))
    ManifestPatch(reason = "forge reconcile: import decomposition.md editable-region edits", ops = edits ++ reorder)

  /** A minimal line-level diff for the §5.4 "edits outside editable markers" refusal: the lines where the candidate
    * render (`expected` — what reconcilable edits would produce) and the on-disk file (`actual`) disagree. Each entry
    * is `1`-based line number with the expected and actual text, so the operator can see exactly which non-editable
    * line they touched. Lengthy diffs are capped so the message stays readable.
    */
  def hunks(expected: String, actual: String, max: Int = 20): Vector[String] =
    val e = expected.linesWithSeparators.map(_.stripLineEnd).toVector
    val a = actual.linesWithSeparators.map(_.stripLineEnd).toVector
    val n = math.max(e.length, a.length)
    val diffs =
      (0 until n).iterator
        .filter(i => e.lift(i) != a.lift(i))
        .map(i =>
          s"  line ${i + 1}:\n    expected: ${e.lift(i).getOrElse("<absent>")}\n    on disk:  ${a
              .lift(i)
              .getOrElse("<absent>")}"
        )
        .toVector
    if diffs.length <= max then diffs
    else diffs.take(max) :+ s"  … and ${diffs.length - max} more differing line(s)"
