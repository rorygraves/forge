package io.forge.core.profile

import io.forge.core.FeatureId
import io.forge.core.log.ActionDraft

/** §19 `profile.snapshot` action (Phase 3 / A5). Recorded at feature start once the profile is loaded, so a replay
  * binds to the profile-as-of-that-run. The payload carries the [[RepoProfile.contentHash]] + `schemaVersion`; the full
  * profile content is the committed `.forge/profile.json` (in git history), and the hash verifies which version was in
  * force. Mirrors the `harness.cache_invalidated` draft-builder idiom in `FileStateCache`.
  */
object ProfileSnapshot:
  val Kind: String = "profile.snapshot"

  def draft(feature: FeatureId, profile: RepoProfile): ActionDraft =
    ActionDraft(
      feature = feature,
      piece = None,
      actor = None,
      role = None,
      kind = Kind,
      payload = ujson.Obj(
        "hash" -> ujson.Str(profile.contentHash),
        "schemaVersion" -> ujson.Num(profile.schemaVersion.toDouble)
      )
    )
