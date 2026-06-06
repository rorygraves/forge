package io.forge.app.command

import cats.effect.{IO, Ref}
import cats.syntax.foldable.*
import io.forge.core.fsm.FsmState
import io.forge.core.log.Action

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Task 4.2.3 — the worker's **feed exporter** (B3): a *read-side* tail of the worker's own per-feature action log that
  * pushes each newly-appended [[Action]] to the daemon as a `worker-event`, plus a `worker-status` whenever an
  * `fsm.transition` names a new `to` state.
  *
  * **Read-side, not a second writer (§6.3.1).** The worker process is the *sole writer* of its feature log (the
  * `Orchestrator`'s `FileActionLog`). This exporter never goes through that `ActionLog`; it reads the on-disk NDJSON
  * file directly (the [[io.forge.app.command.TailCommand]] idiom — `os.read.lines`, re-read each tick, drop the lines
  * already exported) so the export can run concurrently with the loop without touching the writer's in-memory `seq`
  * allocator. A partially-flushed trailing line (a write in progress, or a crash mid-append) simply fails to parse and
  * is left for the next tick — the read side never repairs the file, that is `ActionLog.replay`'s job on the writer.
  *
  * The high-water `seq` is held in a [[Ref]] so the [[follow]] loop and the post-run [[drainOnce]] flush share it: the
  * loop runs while `orch.run` does, then a final drain catches anything written between the last tick and completion.
  */
object WorkerFeedExporter:

  /** Poll cadence for following appends — the same 1s UI cadence as `forge tail`, independent of the §18 PR-poll
    * interval (which is minutes-scale and would make the exported feed lag).
    */
  val FollowInterval: FiniteDuration = 1.second

  /** The "nothing exported yet" watermark. `Action.seq` is 0-based (the first append is `seq = 0`), so the sentinel
    * must be below 0 for the `seq > watermark` filter to admit the very first action.
    */
  val InitialWatermark: Long = -1L

  /** Export every action in `logFile` with `seq` greater than `watermark`'s current value, in `seq` order: each as a
    * `worker-event` carrying the full Action JSON, and — for an `fsm.transition` — an additional `worker-status` with
    * the `to` state name. Advances `watermark` to each action's `seq` **as it is exported**, so a cancellation (the
    * [[follow]] loop being cancelled when `orch.run` completes) resumes from the last fully-exported action: the
    * post-run [[drainOnce]] re-exports at most the single action in flight at the cancel boundary, never the whole
    * batch. Idempotent against repeated calls (a second call with no new appends exports nothing).
    */
  def drainOnce(logFile: os.Path, reporter: WorkerReporter, watermark: Ref[IO, Long]): IO[Unit] =
    watermark.get.flatMap { from =>
      readActions(logFile).flatMap { actions =>
        val fresh = actions.filter(_.seq > from).sortBy(_.seq)
        fresh.traverse_(action => exportAction(reporter, action) *> watermark.set(action.seq))
      }
    }

  /** Follow `logFile`, draining new actions every [[FollowInterval]], until cancelled (the worker loop cancels it when
    * `orch.run` completes, then issues a final [[drainOnce]] on the same `watermark`).
    */
  def follow(logFile: os.Path, reporter: WorkerReporter, watermark: Ref[IO, Long]): IO[Unit] =
    (IO.sleep(FollowInterval) *> drainOnce(logFile, reporter, watermark)).foreverM

  /** Export one action: its full JSON as a `worker-event`, then — if it is an `fsm.transition` — its `to` state name as
    * a `worker-status`.
    */
  private def exportAction(reporter: WorkerReporter, action: Action): IO[Unit] =
    reporter.event(upickle.default.writeJs[Action](action)) *>
      transitionStatus(action).fold(IO.unit)(reporter.status)

  /** The `to` state name of an `fsm.transition` action, or `None` for any other kind. The `to` payload is a `FsmState`
    * encoded by uPickle's Scala-3-enum derivation: a singleton case renders as a bare string (`"Drafting"`), a
    * parameterized case as `{"$type": "...Refining", ...}`. We name it from the JSON directly (last `.`-segment of
    * `$type`) rather than decoding the full `FsmState`, so a schema addition can't break the read-side tail.
    */
  private[command] def transitionStatus(action: Action): Option[String] =
    if action.kind != "fsm.transition" then None
    else action.payload.objOpt.flatMap(_.get("to")).flatMap(stateName)

  private def stateName(to: ujson.Value): Option[String] = to match
    case ujson.Str(s) => Some(s)
    case o: ujson.Obj => o.value.get("$type").flatMap(_.strOpt).map(_.split('.').last)
    case _ => None

  /** The compact case name of an `FsmState` (`"FeatureDone"`, `"Refining"`), via the same encoding the `fsm.transition`
    * `to` payload uses — so the worker loop's explicit terminal `worker-status` names the state the same way the tailed
    * transitions do. Falls back to the runtime class's simple name only if the encoding is ever a non-string,
    * non-`$type` shape (it isn't today).
    */
  def fsmStateName(state: FsmState): String =
    stateName(upickle.default.writeJs[FsmState](state)).getOrElse(state.getClass.getSimpleName.stripSuffix("$"))

  /** Read the on-disk NDJSON log into stamped actions. Read-only (`os.read.lines`); a missing file is empty. A line
    * that fails to parse (a partially-flushed trailing write) ends the read — the survivors before it are a valid
    * prefix, and the unparsed tail is picked up on the next tick once the writer has flushed it.
    */
  private[command] def readActions(logFile: os.Path): IO[Vector[Action]] =
    IO.blocking {
      if !os.exists(logFile) then Vector.empty
      else
        val out = Vector.newBuilder[Action]
        val lines = os.read.lines(logFile).iterator
        var stop = false
        while lines.hasNext && !stop do
          val line = lines.next()
          if line.nonEmpty then
            try out += upickle.default.read[Action](line)
            catch case NonFatal(_) => stop = true
        out.result()
    }
