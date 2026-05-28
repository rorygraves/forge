# Design: `ForgePaths` user-asset accessors

## Goal

Route the install destinations for reviewer assets through `ForgePaths` so no
`.forge/...` string literal escapes the path-seam helper.

## Pieces

1. **p1 — add `userSchemasDir` / `userPromptsDir` / `userTemplatesDir`.**
   Three `val` accessors under `~/.forge/`. (just merged)
2. **p2 — point `AssetInstaller` at the new accessors.** Replace the literal
   paths in the installer with the accessors. (pending)

## Just-merged piece (p1)

Added the three `val` accessors to `ForgePaths`, each derived from
`userForgeDir`. The `ForgePathsSuite` `os.walk` sweep stays green because the
accessors live inside `ForgePaths.scala`. No public surface beyond the three
vals; no behavioural change to existing accessors.

## Refine question

Does merging p1 change anything about the remaining plan (p2)? The accessors
are exactly what p2 consumes; p2's spec is unchanged and still pending. No new
pieces are implied and no design assumption was invalidated.
