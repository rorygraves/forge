package io.forge.agents

import io.forge.core.*

/** Stub prompt envelopes used by `Connector.runHeadlessImplementation` and
  * `runFixup`. The body text is rendered by the orchestrator from
  * `~/.forge/prompts/{implement,fixup}.<driver>.md` templates; the connector
  * passes it through unchanged.
  *
  * NOTE(slice-1): these will gain whatever fields the prompt templates need
  * (e.g. piece spec path, failures.md path, attempt number). Kept minimal for
  * now so the trait signature is honest about what flows through. */

final case class ImplementationPrompt(
    featureId: FeatureId,
    pieceId: PieceId,
    systemPromptPath: os.Path,
    body: String
)

final case class FixupPrompt(
    featureId: FeatureId,
    pieceId: PieceId,
    attempt: Int,
    systemPromptPath: os.Path,
    body: String
)
