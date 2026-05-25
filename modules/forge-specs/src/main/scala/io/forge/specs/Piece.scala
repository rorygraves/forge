package io.forge.specs

import io.forge.core.*
import io.forge.core.Json.given

import java.time.Instant
import upickle.default.ReadWriter

/** §5.1 manifest piece. The nullable fields (`baseSha`, `prNumber`, `mergeCommit`, `mergedAt`) are set at specific FSM
  * transitions named in §11. The validator `Manifest.validate` cross-checks them against `status`.
  */
final case class Piece(
    id: PieceId,
    order: Int,
    title: String,
    summary: String,
    specPath: String,
    acceptanceHash: String,
    status: PieceStatus,
    baseSha: Option[Sha],
    prNumber: Option[PrNumber],
    mergeCommit: Option[Sha],
    mergedAt: Option[Instant],
    attempts: Int
) derives ReadWriter
