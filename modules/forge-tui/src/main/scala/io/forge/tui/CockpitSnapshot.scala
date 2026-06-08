package io.forge.tui

/** Slice 4.4 — the immutable read-model the **cockpit** renders: the whole daemon-supervised fleet (workstreams →
  * workers) for one instance, projected from the daemon's `status` JSON.
  *
  * Where the Slice-2.1 [[TuiSnapshot]] is a single feature folded from *local committed files*, this is the *whole
  * instance over the network*: [[fromStatusJson]] is a pure parse of the daemon `status` response (the §8
  * `committedUsd`/`outstandingUsd` spend totals, the §5 per-workstream `attention` projection), tolerant of absent
  * optional fields exactly as `WorkstreamCommands.renderWorkstreams` reads it today. Keeping the parse pure (no `IO`,
  * no daemon dep) lets `forge-tui` own the cockpit model + view while `forge-app`'s `CockpitCommands` owns the
  * `DaemonClient` round-trip that feeds it — the same split as [[TuiSnapshot]] / `TuiCommand`.
  */
final case class CockpitSnapshot(
    instanceName: String,
    bootCount: Int,
    committedUsd: Double,
    outstandingUsd: Double,
    workstreams: Vector[CockpitWorkstream],
    looseWorkers: Vector[CockpitWorker]
):
  /** Every worker across all workstreams plus the unassigned ones. */
  def allWorkers: Vector[CockpitWorker] = workstreams.flatMap(_.workers) ++ looseWorkers

  def workerCount: Int = allWorkers.size
  def liveCount: Int = allWorkers.count(_.live)

  /** How many workers are flagged as needing a human (the aggregate `attention` projection). */
  def attentionCount: Int = allWorkers.count(_.attentionReason.isDefined)

/** One workstream row: its goal + lifecycle state + aggregate spend + its (ordered) workers, each already carrying its
  * attention flag (resolved from the workstream's `attention` array in [[CockpitSnapshot.fromStatusJson]]).
  */
final case class CockpitWorkstream(
    id: String,
    goal: String,
    status: String,
    committedUsd: Double,
    outstandingUsd: Double,
    workers: Vector[CockpitWorker]
)

/** One worker row: identity + exported FSM status + a rendered liveness label + the optional attention reason (set when
  * the owning workstream's `attention` projection flags this worker).
  */
final case class CockpitWorker(
    workerId: String,
    repo: String,
    feature: String,
    status: String,
    live: Boolean,
    liveness: String,
    eventCount: Int,
    attentionReason: Option[String]
)

object CockpitSnapshot:

  /** Placeholder shown before the first `status` round-trip completes (or for an instance with no workers yet). */
  def loading(instanceName: String): CockpitSnapshot =
    CockpitSnapshot(instanceName, bootCount = 0, committedUsd = 0.0, outstandingUsd = 0.0, Vector.empty, Vector.empty)

  /** Parse the daemon `status` JSON (`io.forge.instance.InstanceState.toStatusJson`) into a cockpit read-model. Pure +
    * total: every field read through an `…Opt` accessor with a default, so a snapshot missing an optional key (a
    * workstreamless worker, a worker with no pid/container) degrades rather than throws.
    *
    * Workers are matched into their workstream by the workstream's `workers` id list; any worker not claimed by a
    * workstream is a [[CockpitSnapshot.looseWorkers]] entry (e.g. a directly-registered host worker with no
    * workstream).
    */
  def fromStatusJson(instanceName: String, json: ujson.Value): CockpitSnapshot =
    val obj = json.objOpt.getOrElse(ujson.Obj().obj)
    def numField(name: String): Double = obj.get(name).flatMap(_.numOpt).getOrElse(0.0)

    val rawWorkers = obj.get("workers").flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)
    val byId: Map[String, ujson.Value] =
      rawWorkers.flatMap(w => w.objOpt.flatMap(_.get("workerId")).flatMap(_.strOpt).map(_ -> w)).toMap

    val rawWorkstreams = obj.get("workstreams").flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty)
    val claimed = scala.collection.mutable.LinkedHashSet.empty[String]

    val workstreams = rawWorkstreams.flatMap(_.objOpt).map { ws =>
      def str(name: String): String = ws.get(name).flatMap(_.strOpt).getOrElse("")
      def num(name: String): Double = ws.get(name).flatMap(_.numOpt).getOrElse(0.0)
      val attention: Map[String, String] =
        ws.get("attention")
          .flatMap(_.arrOpt)
          .map(_.toVector)
          .getOrElse(Vector.empty)
          .flatMap(_.objOpt)
          .flatMap { a =>
            for
              wid <- a.get("workerId").flatMap(_.strOpt)
              reason <- a.get("reason").flatMap(_.strOpt)
            yield wid -> reason
          }
          .toMap
      val workerIds = ws.get("workers").flatMap(_.arrOpt).map(_.toVector).getOrElse(Vector.empty).flatMap(_.strOpt)
      val workers = workerIds.flatMap { wid =>
        claimed += wid
        byId.get(wid).map(raw => workerFrom(raw, attention.get(wid)))
      }
      CockpitWorkstream(
        id = ws.get("workstreamId").flatMap(_.strOpt).getOrElse("?"),
        goal = str("goal"),
        status = str("status"),
        committedUsd = num("committedUsd"),
        outstandingUsd = num("outstandingUsd"),
        workers = workers
      )
    }

    val looseWorkers = rawWorkers.flatMap { w =>
      val id = w.objOpt.flatMap(_.get("workerId")).flatMap(_.strOpt)
      id.filterNot(claimed.contains).map(_ => workerFrom(w, None))
    }

    CockpitSnapshot(
      instanceName = instanceName,
      bootCount = numField("bootCount").toInt,
      committedUsd = numField("committedUsd"),
      outstandingUsd = numField("outstandingUsd"),
      workstreams = workstreams,
      looseWorkers = looseWorkers
    )

  private def workerFrom(raw: ujson.Value, attentionReason: Option[String]): CockpitWorker =
    val w = raw.objOpt.getOrElse(ujson.Obj().obj)
    def str(name: String): Option[String] = w.get(name).flatMap(_.strOpt)
    val live = w.get("live").flatMap(_.boolOpt).getOrElse(false)
    val exit = w.get("exitCode").flatMap(_.numOpt).map(_.toInt)
    val pid = w.get("pid").flatMap(_.numOpt).map(_.toLong)
    val container = str("containerId")
    val liveness =
      exit match
        case Some(code) => s"exited($code)"
        case None =>
          container match
            case Some(c) => s"live(container=${c.take(12)})"
            case None =>
              pid match
                case Some(p) => s"live(pid=$p)"
                case None => if live then "live" else "not-live"
    CockpitWorker(
      workerId = str("workerId").getOrElse("?"),
      repo = str("repo").getOrElse(""),
      feature = str("feature").getOrElse("?"),
      status = str("status").getOrElse("?"),
      live = live,
      liveness = liveness,
      eventCount = w.get("eventCount").flatMap(_.numOpt).map(_.toInt).getOrElse(0),
      attentionReason = attentionReason
    )
