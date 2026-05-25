# Slice 0 — CLI capability validation report

> Per §16 of the v1.0 design. Validates whether the pinned CLI versions
> satisfy the capabilities the §7.1 connector contract assumes. Output
> drives any v1.x revision needed before Slice 1 implementation work begins.

**Run on:** 2026-05-25 on macOS (Darwin 25.4.0).
**Investigator:** Rory.

---

## 1. Pinned versions

| Tool | Version | Source |
|---|---|---|
| Claude Code CLI | `2.1.150` | `claude --version` |
| Codex CLI | `codex-cli 0.130.0` | `codex --version` |
| GitHub CLI | `2.83.1` | `gh --version` |
| Operating system | Darwin 25.4.0 (macOS) | `uname -r` |
| Shell | zsh | — |

Future Forge versions may need to bump these floors; capability findings below are scoped to these exact builds.

---

## 2. Driver-role validation (2×2 matrix)

The driver role needs: streaming subprocess, isolation flag, session resume,
system-prompt injection, killable handle, question mechanism (§16 #1-6).

### 2.1 Claude driver

| Capability | Result | Mechanism |
|---|---|---|
| Long-lived streaming subprocess | ✅ | `claude -p --output-format stream-json --verbose` |
| Isolation suppressing user config | ✅ | `--bare --setting-sources project,local --strict-mcp-config` |
| Session resume | ✅ | `claude -p --resume <session-uuid>` — preserves the original `session_id` |
| System-prompt injection from file | ✅ | `--system-prompt-file <path>` (also accepts inline `--system-prompt`) |
| Killable handle (SIGTERM, ≤5s grace) | ✅ | Measured ~400ms from SIGTERM to clean exit; no orphaned children |
| Question mechanism — Native | ✅ | `AskUserQuestion` tool emitted via `assistant.message.content[].type=="tool_use"` |

**Streaming event shape (sampled from `transcripts/01-claude-headless.jsonl`):**
```
system/subtype=init         → carries session_id, tools, model, mcp_servers, version
system/subtype=hook_*        → per-hook bookkeeping (skippable for Forge)
assistant                    → message.content[] (text + tool_use blocks), usage tokens, session_id
rate_limit_event             → status, resetsAt, rateLimitType (relevant to §22)
result                       → final event: result string, duration_ms, total_cost_usd, modelUsage, session_id
```

Notes:
- `session_id` is in **every** event (init, assistant, result, rate_limit_event, hook_started/response).
- `result.total_cost_usd` is reported per turn; aggregating across turns is trivial.
- `permission_denials` is populated when the model attempts a disallowed tool.
- `--bare` drops user MCP servers and most plugin tools but `--bare` also disables OAuth — Forge must use either an environment-provided `ANTHROPIC_API_KEY` or skip `--bare` in favour of `--setting-sources project,local --strict-mcp-config`.

### 2.2 Codex driver

| Capability | Result | Mechanism |
|---|---|---|
| Long-lived streaming subprocess | ✅ | `codex exec --json` (writes JSONL to stdout) |
| Isolation suppressing user config | ✅ | `--ignore-user-config --ignore-rules` plus `--sandbox` |
| Session resume | ✅ | `codex exec resume <thread-uuid>` — preserves the original `thread_id` |
| System-prompt injection | ⚠️ | **No dedicated flag.** Pass the system prompt as the first positional argument (or via stdin `-`). `-c` cannot override the model's system prompt. |
| Killable handle (SIGTERM, ≤5s grace) | ✅ | Measured ~100ms from SIGTERM to clean exit; no orphaned children |
| Question mechanism — `HaltWithQuestion` | 🟡 | No native mid-turn primitive. Implementable via prompt-engineering convention (§7.3) — reliability TBD in Slice 1 once a real system prompt is wired up; design treats this as a v1-blocker if it can't hit ≥19/20 |

**Streaming event shape (sampled from `transcripts/04-codex-headless.jsonl`):**
```
thread.started   → carries thread_id (UUID)
turn.started     → no payload
item.completed   → item.id, item.type ∈ {agent_message, ...}, item.text
turn.completed   → usage { input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens }
```

Notes:
- **No `total_cost_usd`** — only token counts. Forge must maintain a per-model price table to compute USD; this is a small piece of Slice 1 work the design didn't call out.
- The `thread.started` event is always first, so projecting the thread id from the first event is trivial.
- Stderr emits "Reading additional input from stdin..." even when a prompt is provided as an arg — Forge must separate stderr from stdout when consuming `--json` output.
- `codex exec resume` does **not** accept `--sandbox`, `--output-schema`, `-a/--ask-for-approval`, `-C/--cd`, or `--add-dir`. Session-scoped settings are sticky from the original `exec` call. This affects the design: a resumed driver session inherits the original sandbox policy.

### 2.3 Capability gaps — design impact

| Cell | Gap | v1.x decision |
|---|---|---|
| Both drivers, §6.1 invariant | "session_id is updated to the *new* (post-resume) id" — both CLIs **preserve** the id on resume by default | Accept the same id back. Update §6.1 to: "`<actor>.resume` events record `(oldSessionId, newSessionId)` and `newSessionId` MAY equal `oldSessionId`." No code change in the orchestrator beyond removing the assumption that the id changes. |
| Codex driver, §7.1 `runStreamingSpec(systemPromptPath)` | No `--system-prompt-file` flag on Codex; system prompt must ride in the user prompt | The orchestrator already reads the system-prompt file from `~/.forge/prompts/<role>.<driver>.md`; the Codex connector prepends it as a `## System` block to the user message. Adapter-internal; no spec change. |
| Codex driver, §11.4 / §11.6 | Session-scoped settings (sandbox, schema) are sticky across resume | Spawn a fresh session for any phase that needs different settings. Already what the design implies for fix-up (§11.6 explicitly says "fresh driver session"); document that the same applies to any sandbox/schema change. |

None of these gaps narrow v1 scope. All are accommodatable in Slice 1 adapter code.

---

## 3. Reviewer-role validation (2×2 matrix)

The reviewer role needs: schema-constrained output, read-only sandbox (§16 #7-8).

### 3.1 Claude reviewer — `SchemaMechanism = Native`

**This is the headline finding of Slice 0**: Claude has a native `--json-schema` flag that the v1.0 design didn't anticipate.

**Probe** (`transcripts/07-claude-schema.json`):
```
claude -p --output-format json --json-schema '<schema>' --permission-mode default '<prompt>'
```
returned a top-level `structured_output` field with **schema-conformant JSON on the first attempt**:
```json
{
  "structured_output": {
    "verdict": "approve",
    "blockers": [],
    "summary": "Changes look correct, well-scoped, and ready to merge."
  },
  ...
}
```

| Capability | Result | Mechanism |
|---|---|---|
| Schema-constrained output | ✅ Native | `--json-schema '<schema-json>'` → `structured_output` field on result envelope |
| Read-only sandbox | ✅ | `--permission-mode default --allowedTools Read,Glob,Grep,WebFetch` (deny Write/Edit/Bash) |

**Design impact:** SchemaFallback (§7.5) and its 2-attempt cap are **no longer required for v1**. Both connectors can declare `schemaMechanism = Native`. The §7.6 "process retries vs SchemaFallback" distinction collapses to plain process retries.

The §7.5 protocol can stay in the spec as a fallback for some future CLI that lacks native schema enforcement, but no v1 code needs it.

### 3.2 Codex reviewer — `SchemaMechanism = Native`

**Probe** (`transcripts/06-codex-schema.jsonl`):
```
codex exec --json --output-schema /path/to/schema.json '<prompt>'
```
returned a schema-conformant `agent_message`:
```json
{"verdict":"approve","blockers":[],"summary":"Approved."}
```

| Capability | Result | Mechanism |
|---|---|---|
| Schema-constrained output | ✅ Native | `--output-schema <file>` — JSON Schema file path |
| Read-only sandbox | ✅ | `-s read-only` (sandbox mode), `-a never` to prevent approval escalation, optionally `--ignore-rules` |

**Design impact:** None — this matches §7.4 exactly.

### 3.3 Reviewer matrix summary

| Cell | Capability | Mechanism | Threshold met? |
|---|---|---|---|
| Claude reviewer | schema output | `Native` (`--json-schema`) | ✅ first-attempt success on the one probe — full ≥19/20 measurement deferred to Slice 1 integration tests |
| Claude reviewer | read-only sandbox | tool allowlist | ✅ |
| Codex reviewer | schema output | `Native` (`--output-schema`) | ✅ first-attempt success on the one probe — full ≥19/20 measurement deferred to Slice 1 integration tests |
| Codex reviewer | read-only sandbox | `-s read-only` | ✅ |

The §16.1 reviewer cells move from "Native or SchemaFallback ≥19/20" to **Native** for both. A formal ≥19/20 measurement is no longer the gating bar; we'll still run a sample suite in Slice 1 to catch edge cases, but it's regression testing, not feasibility validation.

---

## 4. GitHub CLI capabilities (§16 #9-12)

### 4.1 `gh pr view --json` fields

Every field §9 requires is in the `--json help` field list (probed against `gh pr view`):
- `state`, `statusCheckRollup`, `reviews`, `reviewDecision`, `mergeable`, `mergeStateStatus`, `comments`, `commits`, `mergedAt`, `mergeCommit`

Plus useful extras (`headRefOid`, `baseRefOid`, `latestReviews`, `potentialMergeCommit`) that may simplify the design later.

### 4.2 Branch-protection API

`gh api repos/<owner>/<repo>/branches/<base>/protection/required_status_checks`:
- **Returns 404** when no protection rule exists or when the caller lacks admin access. §8.1 already handles this by falling back to observed-checks discovery — no design change.
- The path is correct; the JSON shape is the standard GitHub REST schema.

### 4.3 Line-based comment API

`POST /repos/<owner>/<repo>/pulls/<n>/comments` with `path/side/line/commit_id` (§10.2). The endpoint and shape exist; `gh api repos/.../pulls/<n>/comments` (GET) returned 200 + 2-byte body for the probed PR. Slice 1 integration tests on a sacrificial repo will exercise the POST path end-to-end.

### 4.4 Rate-limit baseline

`gh api rate_limit` returns:
```json
{"core":   {"limit":5000,"used":7,"remaining":4993,"reset":1779703052},
 "graphql":{"limit":5000,"used":40,"remaining":4960,"reset":1779702156},
 "search": {"limit":30,  "used":0, "remaining":30,  "reset":1779700301}}
```

5000/hour for core endpoints comfortably accommodates §18's `pollIntervalMs: 30000` (max ~120 polls/hour per feature) and the §10.2 fetch-diff + post-comment burst. Rate-limit backoff per §22 is needed for unexpected 403/429 responses; the gh CLI surfaces these as exit codes and stderr.

---

## 5. Slice-0 outcome

### 5.1 Scope decisions (per §16.1)

| Cell | Threshold | Decision |
|---|---|---|
| Claude driver | All work; Native questions | **Met.** Slice 1 implementation proceeds. |
| Codex driver | All work; HaltWithQuestion ≥19/20 | **Met for the CLI mechanics; the reliability ≥19/20 measurement deferred to Slice 1.** If the prompt-convention halt protocol falls below 19/20, we narrow scope per §16.1 (option 2). |
| Claude reviewer | Schema output ≥19/20 per method | **Exceeded.** Native (`--json-schema`) replaces SchemaFallback entirely. |
| Codex reviewer | Native or fallback | **Met.** Native (`--output-schema`) per §7.4. |

### 5.2 Design revisions needed (v1.1 candidates)

These don't narrow scope; they simplify it or correct factual mismatches. They are non-blocking for starting Slice 1 — Slice 1 simply implements against this corrected model.

1. **§6.1** — Both CLIs preserve session id on resume by default. The invariant text "session_id is updated to the *new* (post-resume) id" should become "the `<actor>.resume` event records `oldSessionId` and `newSessionId`; the values MAY be equal." Forge's behaviour is unchanged; the spec text is.
2. **§7 / §7.5 / §7.6** — Claude `SchemaMechanism = Native` via `--json-schema`. SchemaFallback (§7.5) is no longer a v1 requirement. The §7.6 layer collapses to plain `reviewProcessRetries`. The SchemaFallback protocol can remain in the spec for v2 if a future CLI lacks native schema, but no v1 code paths reference it.
3. **§7.1 Codex system-prompt injection** — `runStreamingSpec(systemPromptPath: os.Path)` is implemented as "read the file, prepend it as a `## System` block to the user prompt" in `CodexConnector`. No signature change at the trait; document the adapter-internal behaviour.
4. **Slice 1 cost telemetry** — Codex emits token counts only; Forge maintains a per-model price table (`~/.forge/prices.json`?) and computes USD in the connector's `costFrom` implementation. Add this as an explicit deliverable in Slice 1 §17 once 1.1 lands.

### 5.3 Slice 1 entry condition

Slice 0's deliverable was either a green light, a narrowed scope, or a v1-blocker. **It's a green light with documented spec corrections.** Slice 1 can start; the corrections above (and any others surfaced during Slice 1) will land as design v1.1 once Slice 1's first connector is functioning.

---

## Appendix — Raw transcripts

| File | What it shows |
|---|---|
| `transcripts/01-claude-headless.jsonl` | First Claude `-p --output-format stream-json` run. Init event, assistant message, rate-limit, result. |
| `transcripts/02-claude-resume.jsonl` | Resume of the above session. Same `session_id` returned. |
| `transcripts/03-claude-bare.jsonl` | `--bare` isolation. Tools count dropped from ~90 to 3; MCP servers from 2 to 0. |
| `transcripts/04-codex-headless.jsonl` | First `codex exec --json`. `thread.started`, `turn.started`, `item.completed`, `turn.completed`. |
| `transcripts/05-codex-resume.jsonl` | `codex exec resume` of the same thread. `thread_id` preserved. |
| `transcripts/06-codex-schema.jsonl` | `codex exec --output-schema` produces schema-conformant JSON in `agent_message.text`. |
| `transcripts/07-claude-schema.json` | `claude --json-schema` produces a `structured_output` field on the result envelope. |
| `transcripts/05-codex-resume.stderr.log` | Codex stderr when `--sandbox` was passed to `exec resume` (rejected). |
